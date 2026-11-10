# harness — нагрузочный тест одной фазы

Запускает одну фазу прогона: N серверных JVM Apache Ignite на jar'ах конкретной сборки, затем loader-JVM
(thick-клиент), который создаёт кэш, предзагружает ключи и даёт put/get-нагрузку. Латентность — HdrHistogram
на стороне клиента, серверные метрики (GC, аллокации, CPU, heap) — из JFR-записи каждого узла. Фаза = свежие JVM;
ABBA-сравнение — это четыре фазы (A1 B1 B2 A2), которые запускает контроллер.

## Сборка

```bash
mvn -q package                     # нужен JDK 17
```

Получается `modules/harness/target/harness.jar` (shaded: Jackson и HdrHistogram перепакованы, Ignite в нём нет).
Harness компилируется против релиза `ignite-core` из `pom.xml` (`ignite.version`), а работает с jar'ами любого
коммита 2.x: используется только давно стабильный public API.

## Jar'ы Ignite

- Для локальной отладки без сборки Ignite: `modules/harness/dev-libs.sh` кладёт релизную `ignite-core` с зависимостями
  в `modules/harness/target/dev-libs`.
- Для коммита: в клоне apache/ignite
  `mvn -pl modules/core -am -Dmaven.test.skip=true -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -Dcheckstyle.skip=true install`,
  затем `mvn -pl modules/core dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=LIBS`
  и `cp modules/core/target/ignite-core-*.jar LIBS/` (без `-sources`/`-tests`/`original-`). Это делает контроллер.

## Запуск

```bash
java -Xmx128m -jar modules/harness/target/harness.jar run \
  --libs modules/harness/target/dev-libs --scenario putget-thin-t16 --quick \
  --out /tmp/smoke --run-id smoke-1 --phase S
java -jar modules/harness/target/harness.jar list    # все сценарии (JSON): id, версия, топология, тайминги, исходники
```

`--quick` — 2 с прогрева + 5 с замера вместо значений сценария: проверить сценарий или конвейер.
`--stand FILE` — запустить JVM на хостах удалённого стенда (см. ниже).
`--scale MEMORY_MB,FILL_PCT,THREADS_FACTOR,1` — размеры стенда (см. ниже). `--warmup-sec`, `--measure-sec` — другие тайминги, чем у сценария.

```bash
java -jar modules/harness/target/harness.jar check --dir data       # эта машина, отчёт в JSON
java -jar modules/harness/target/harness.jar check --stand FILE     # хосты стенда: каждый сам по себе и связь между ними
java -jar modules/harness/target/harness.jar sweep --stand FILE     # снять с хостов JVM и каталоги убитых фаз
```

Коды выхода: 0 — фаза завершена, 1 — фаза упала (детали в `result.json` и логах), 2 — неверные аргументы.
Launcher-JVM маленькая (`-Xmx128m` хватает), сама Ignite в ней не загружается; размеры heap серверов и loader'а
задаются сценарием. Все дочерние JVM помечены `-Dignite.firewatch.run=<run-id>`: зависшие процессы убиваются
`pkill -f ignite.firewatch.run=<run-id>`.

Последовательность фазы: старт серверов → ожидание `HARNESS_READY` в логе каждого → старт loader'а → loader
ждёт топологию, создаёт кэш, предзагружает ключи стримером → warmup → measure → loader пишет `loader.json` →
серверам SIGTERM (узел останавливается штатно, JFR дописывается) → разбор JFR → `result.json`, `series.json`.
При любой ошибке дочерние JVM убиваются, `result.json` получает `status: failed` и `error`.

## Что лежит в `--out`

| Файл | Что |
|---|---|
| `result.json` | итог фазы (ниже) |
| `series.json` | посекундные ряды всей фазы, индекс = секунды от старта нагрузки, `measureStartIndex` — начало окна измерения |
| `scenario.json` | эффективный сценарий (с дефолтами), его sha256 — `scenarioHash` в результате |
| `node-i.log`, `node-i.jfr`, `node-i.cmd` | лог, JFR-запись и командная строка серверного узла i |
| `loader.log`, `loader.json`, `loader.cmd` | то же для loader'а; `loader.json` — сырой результат клиента |
| `ignite-firewatch.jfc` | JFR-профиль (GC, GCHeapSummary, CPULoad 1 s, ThreadAllocationStatistics 1 s, ExecutionSample 10 ms) |
| `profile.json` | горячие методы окна measure по `jdk.ExecutionSample` (сумма по узлам): `self` — метод на вершине стека, `total` — где угодно в стеке, по 200 записей |
| `work/` | рабочие каталоги Ignite, temp и JFR-репозитории дочерних JVM |

### `result.json`

- `status` (`ok`/`failed`), `error`, `harnessVersion`, `scenarioName`, `scenarioHash`, `runId`, `phase`,
  `igniteVersion` (из `ignite.version()`), `startedAt`, `measureStartAt`, `warmupSec`, `measureSec`, `wallSec`,
  `connectMs`, `preloadMs`, `errors` (неудачные операции).
- Если упало больше 1 % операций нагрузки (с прогревом), фаза падает с ошибкой `N of M operations failed (P%), the first: …`:
  такой прогон меряет поломку, а не скорость. Первую ошибку loader кладёт в `loader.json` (`firstError`).
- `startMs` и версию узла launcher берёт из строки `HARNESS_READY` и читает лог узла построчно до неё, поэтому лог в сотни МБ
  ему не мешает.
- `throughput` — ops/s по посекундным замерам окна measure: `mean`, `stdev`, `min`, `max`, `total`, `samples`.
- `latencyUs` — `mean`, `p50`, `p90`, `p99`, `p999`, `max` (микросекунды, HdrHistogram за всё окно measure;
  при фиксированном rate латентность считается от запланированного момента операции).
- `gc` — за окно measure, сумма по серверам: `count`, `totalPauseMs`, `maxPauseMs`, `pausePct`, `allocMbPerSec`.
- `cpu` — проценты от всех CPU машины: `serverJvmPct` (сумма по серверам, user+system), `machinePct`,
  `loaderJvmPct`.
- `heap` — `afterGcMbMean` (сумма по серверам), `committedMbMax`.
- `nodes[]` — то же по каждому узлу плюс имена файлов; `env` — JDK, ОС, число CPU, память машины.

### `series.json`

`t[]`, `ops[]`, `p50Us[]`, `p99Us[]`, `loaderCpuPct[]` (клиент) и `serverCpuPct[]`, `machineCpuPct[]`,
`gcPauseMs[]`, `allocMb[]`, `heapUsedMb[]` (из JFR, `null` там, где выборки нет).

## Сценарий

Сценарий — класс в пакете `org.apache.ignite.firewatch.scenarios` (`modules/harness/src/main/java/org/apache/ignite/firewatch/scenarios/`),
наследник `org.apache.ignite.firewatch.harness.api.Scenario` с аннотацией `@Bench`. Harness находит такие классы сам
(`Catalog`), поэтому добавить сценарий = добавить файл, удалить = удалить файл.

```java
@Bench(id = "putget-thin-t16", version = 1,
    description = "Main tracker: thin client, 16 threads, 1 in-memory server, 100k keys x 100 B, 70% get, closed loop",
    loader = @Loader(client = Client.THIN, threads = 16, heapMb = 192))
public class PutGetThinT16 extends PutGetInMemory {
}
```

- `@Bench`: `id` (стабильная идентичность, на него ссылаются задачи), `version` (поднять, когда меняется
  измеряемое: хэш сценария = `sha256(id:version)`), `description`, `@Servers(count, heapMb, jvmOpts, group)`,
  `@Loader(client, threads, count, heapMb, jvmOpts, rateOpsPerSec, group)`, `warmupSec`, `measureSec`,
  `startTimeoutSec`, `setupTimeoutSec`. Это чистые данные: каталог читает их без Ignite в classpath.
- Методы класса (выполняются в JVM узлов и loader'а, где есть jar'ы коммита):
  `configureServer(IgniteConfiguration, Env)` — только отличия от умолчаний Ignite (discovery, client connector,
  local host и work directory уже заданы harness'ом); `configureClient` / `configureThinClient` для loader'а;
  `setup(Session)` — кеши, `session.activate()` для persistence, прогрузка (её длительность — метрика preload);
  `op(Session, ThreadLocalRandom)` — одна операция, вызывается из всех потоков нагрузки, её латентность пишется.
- `Env.resource(name)` копирует ресурс из `harness.jar` (например `ssl/node.jks`) в рабочую папку узла.
- `rateOpsPerSec = 0` — closed loop без пауз; больше нуля — open loop, rate делится между потоками.
- Heap задаётся `-Xms=-Xmx`; к `jvmOpts` всегда добавляются `--add-opens` для JDK 17, `-DIGNITE_QUIET=true`,
  `-DIGNITE_UPDATE_NOTIFIER=false`, `-Djava.net.preferIPv4Stack=true`, `-Djava.io.tmpdir` внутри `--out` и для
  серверов `-XX:StartFlightRecording` с `ignite-firewatch.jfc`.
- Сценарии компилируются против релизной `ignite-core`, а запускаются с jar'ами коммита: использовать
  давно стабильный публичный API, иначе на старых коммитах фаза упадёт с понятной ошибкой.
- Группы хостов (`group`) пока не используются, есть только `local`; `@Loader(count)` больше 1 не поддерживается.
- Размеры стенда: `Scale.current()` даёт регион (`regionMb(база, sql)`) и число записей (`entries(база, байт на запись, копий, sql)`). Без `--scale`
  это те же базовые числа, поэтому история не рвётся. Новый сценарий должен брать размеры отсюда, а не из констант.

## Удалённый стенд

С `--stand FILE` JVM фазы работают на Linux-хостах, launcher остаётся на этой машине и управляет ими по ssh. Агентов на хостах нет.
На хосте нужны JDK 17, вход по ключу, `rsync` и `pkill`, sudo не нужен.

```json
{"hosts": [{"address": "bench-1", "user": "bench", "port": 22, "role": "servers", "javaHome": "/usr/lib/jvm/java-17",
            "workDir": "/opt/firewatch", "dataAddress": "10.9.0.1"},
           {"address": "bench-2", "user": "bench", "port": 22, "role": "loaders", "javaHome": "/usr/lib/jvm/java-17",
            "workDir": "/opt/firewatch", "dataAddress": null}],
 "sshKey": "", "sshKnownHosts": "",
 "fingerprints": {"bench-1": {"cpu": "…", "cpus": "16", "memGb": "32", "kernel": "Linux 6.8.0", "jdk": "17.0.10+7"}},
 "runPrefix": "598ae77e-"}
```

- Раскладка детерминирована: узел `i` идёт на `servers[i % N]` в порядке файла, loader на первый хост `loaders`. Ignite слушает `dataAddress`,
  если он задан, ssh всегда ходит на `address`.
- Перед фазой `rsync` кладёт `harness.jar` в `<workDir>/harness.jar` и jar'ы сборки в `<workDir>/builds/<ключ сборки>/libs`: они остаются на
  хосте, и повторная фаза ничего не копирует. Каталог фазы — `<workDir>/runs/<run-id>`.
- ssh держит соединение всю жизнь JVM и приносит её вывод в локальный лог, поэтому `HARNESS_READY`, логи и код выхода работают как локально.
- У каждой JVM свой маркер `-Dignite.firewatch.run=<run-id>-<label>`: сигналы идут отдельным вызовом ssh с `pkill -f`, и останавливается ровно одна JVM.
- После остановки `rsync` забирает `node-i.jfr` и `loader.json`, результаты считаются на этой машине, каталог фазы на хосте удаляется, в том
  числе при сбое. В `result.json` появляются `nodes[].host`, `loaderHost` и `hosts` — что каждый хост сказал о себе перед фазой.
- В начале фазы снимается всё, что осталось на хосте от этого стенда (`runPrefix`), на хосте остаётся 20 последних сборок. `HostProbe`, запущенный там на
  JDK стенда, рассказывает о машине; если она разошлась с `fingerprints`, фаза падает с указанием, что изменилось.
- `check` кладёт на хост `harness.jar` и запускает `HostProbe`: факты о машине, затем по очереди открывает на каждом хосте порты Ignite и пробует
  их с остальных. Тем же соединением сравниваются часы: сервер отвечает своим временем, время в пути вычитается.
