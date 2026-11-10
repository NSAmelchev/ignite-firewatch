# Development notes

## Layout

| Path | What |
|---|---|
| `modules/harness` | runs one phase: starts the Ignite server JVMs and a loader, writes `result.json`; the scenarios |
| `modules/controller` | queue, git mirror, builds, ABBA statistics, bisection, REST API, web UI |
| `modules/assembly` | the distribution: `bin/`, `config/`, `libs/controller.jar` and `libs/harness.jar` in a zip |
| `config/ignite-firewatch.properties` | settings of the controller |
| `bin/firewatch.sh` | runs the controller without Docker |
| `docker/` | image, compose file and `deploy.sh` |

## Build and test

JDK 17, Maven 3.9 and git; `node` is optional.

```bash
mvn package                  # checkstyle, tests, the jars, target/ignite-firewatch-<version>-bin.zip
mvn -q -DskipTests package   # the same without tests
```

- Checkstyle runs first with the rules of Apache Ignite itself (`org.apache.ignite:ignite-checkstyle`): javadoc on
  every type, field and method, `@param` and `@return` on public methods, an empty line between members, the
  import order, Ignite's abbreviations (`cnt`, `cmd`, `ldr`...), 140 columns. A violation fails the build.
- The pipeline tests run the controller with a fake build and a shell script instead of the harness; the git tests
  create repositories in `java.io.tmpdir` (`-Dtest.tmpdir=...` moves them). The UI smoke test renders every page
  with `node` and is skipped without it.

## Run locally

```bash
modules/harness/dev-libs.sh                  # Ignite release jars into modules/harness/target/dev-libs(-sql)
java -jar modules/harness/target/harness.jar list
java -jar modules/harness/target/harness.jar run --libs modules/harness/target/dev-libs \
  --scenario putget-thin-t16 --quick --out /tmp/phase   # one phase: 2 s warmup, 5 s measure
IGNITE_FIREWATCH_FAKE_BUILD=1 bin/firewatch.sh start     # UI on :8090, release jars instead of building commits
```

## Stand

- Results live in the Docker volume `ignite-firewatch-data` (SQLite and run files); the git mirror, Maven repository
  and builds in `ignite-firewatch-cache`, which can be wiped. Both are external, so `docker compose down -v` keeps
  them. Backup: `docker run --rm -v ignite-firewatch-data:/data -v "$PWD":/out alpine tar czf /out/data.tgz -C / data`.
- Remote benchmark hosts (Settings → Stand) need an ssh key: put `key` (mode 600) and `known_hosts` into
  `docker/ssh/`; the container sees them read-only as `/ssh`.
- `docker/deploy.sh` deploys to another host; its values (host, docker context, time zone) go to the git-ignored
  `docker/deploy.env`, see `docker/deploy.env.example`.

## Add a scenario

A scenario is a class in `modules/harness/src/main/java/org/apache/ignite/firewatch/scenarios/` that extends
`Scenario` and carries `@Bench`. The harness finds it by itself: add the file, rebuild, deploy.

```java
@Bench(id = "putget-thin-t16", version = 1,
    description = "Main tracker: thin client, 16 threads, 1 in-memory server, 100k keys x 100 B, 70% get, closed loop",
    loader = @Loader(client = Client.THIN, threads = 16, heapMb = 192))
public class PutGetThinT16 extends PutGetInMemory {
}
```

- `id` never changes: jobs and history refer to it. Raise `version` when what the scenario measures changes, so
  trends and noise floors start a new series. `title` can be renamed at will.
- `@Servers` and `@Loader` set the topology, heap, client (thin or thick), threads and an optional fixed rate.
- `configureServer`, `configureClient`, `configureThinClient`: only what differs from Ignite's defaults; discovery,
  ports and work directories are set by the harness.
- `setup(Session)` creates caches and loads the data (measured as preload); `op(Session, rnd)` is one operation,
  called from every load thread, and its latency is what gets recorded.
- Take sizes from `Scale.current()` rather than constants, so a stand with its own sizes fills its memory.
- SQL scenarios declare `modules = {"indexing", "calcite"}`, and the controller builds those modules for them.
- The harness is compiled against an Ignite release and runs with the jars of the commit under test: use public
  API that has been stable for long.
- Try it with `harness.jar run --quick`; on the stand run A/A for it (Noise page) to get its own noise floor.

## Code

- Java 17 without frameworks: the JDK's HTTP server, SQLite, git as a command line tool.
- The web UI is plain ES modules and uPlot in `modules/controller/src/main/resources/web`, without a build step.
- Code, comments, UI strings and `docs/` are in English; the module READMEs are in Russian.
