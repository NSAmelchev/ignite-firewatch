# harness — the load test of one phase

Runs one phase of a run: N Apache Ignite server JVMs on the jars of one build, then a loader JVM (a thick or thin
client) that creates the cache, preloads the keys and gives the put/get load. Latency is an HdrHistogram on the client
side; the server metrics (GC, allocation, CPU, heap) come from the JFR recording of every node. A phase is fresh JVMs; an
ABBA comparison is four phases (A1 B1 B2 A2), which the controller runs.

## Build

```bash
mvn -q package                     # needs JDK 17
```

The result is `modules/harness/target/harness.jar` (shaded: Jackson and HdrHistogram are relocated, Ignite is not in it).
What a scenario is written against (`@Bench`, `Scenario`, `Session`, `Env`, `Scale`) is the module `modules/harness-api`:
the harness carries it, the jar of a plugin compiles against it (see Plugins below).
The harness is compiled against the `ignite-core` release of `pom.xml` (`ignite.version`) and runs with the jars of any
2.x commit: it uses only public API that has been stable for long.

## Ignite jars

- To debug locally without building Ignite: `modules/harness/dev-libs.sh` puts the `ignite-core` release with its
  dependencies into `modules/harness/target/dev-libs`, and the same with `ignite-indexing` and `ignite-calcite` into
  `dev-libs-sql`.
- For a commit, in a clone of apache/ignite:
  `mvn -pl modules/core -am -Dmaven.test.skip=true -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -Dcheckstyle.skip=true install`,
  then `mvn -pl modules/core dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=LIBS`
  and `cp modules/core/target/ignite-core-*.jar LIBS/` (without `-sources`/`-tests`/`original-`). The controller does this.

## Run

```bash
java -Xmx128m -jar modules/harness/target/harness.jar run \
  --libs modules/harness/target/dev-libs --scenario putget-thin-t16 --quick \
  --out /tmp/smoke --run-id smoke-1 --phase S
java -jar modules/harness/target/harness.jar list    # every scenario (JSON): id, version, topology, timings, sources
java -jar modules/harness/target/harness.jar list --plugin JAR   # what a plugin says of itself, and its scenarios
```

`--quick` is 2 s of warmup and 5 s of measurement instead of the values of the scenario: to try a scenario or the pipeline.
`--stand FILE` runs the JVMs on the hosts of a remote stand (see below).
`--scale MEMORY_MB,FILL_PCT,THREADS_FACTOR,1` gives the sizes of the stand (see below). `--warmup-sec` and `--measure-sec`
give other timings than the scenario's. `--plugin JAR` runs a scenario of a plugin (see below).

```bash
java -jar modules/harness/target/harness.jar check --dir data       # this machine, the report as JSON
java -jar modules/harness/target/harness.jar check --stand FILE     # the hosts of a stand: each on its own and the way between them
java -jar modules/harness/target/harness.jar sweep --stand FILE     # take the JVMs and directories of killed phases off the hosts
```

Exit codes: 0 the phase finished, 1 the phase failed (details in `result.json` and the logs), 2 wrong arguments.
The launcher JVM is small (`-Xmx128m` is enough) and does not load Ignite itself; the heap sizes of the servers and the
loader come from the scenario. Every child JVM is marked `-Dignite.firewatch.run=<run-id>`: hung processes are killed with
`pkill -f ignite.firewatch.run=<run-id>`.

The order of a phase: the servers start → `HARNESS_READY` is awaited in the log of each → the loader starts → the loader
waits for the topology, creates the cache, preloads the keys with a streamer → warmup → measure → the loader writes
`loader.json` → SIGTERM to the servers (the node stops gracefully, JFR is written out) → JFR is parsed → `result.json`,
`series.json`. On any error the child JVMs are killed and `result.json` gets `status: failed` and `error`.

## What is in `--out`

| File | What |
|---|---|
| `result.json` | the outcome of the phase (below) |
| `series.json` | per-second series of the whole phase, index = seconds from the start of the load, `measureStartIndex` is the start of the measurement window |
| `scenario.json` | the effective scenario (with the defaults); its sha256 is `scenarioHash` in the result |
| `node-i.log`, `node-i.jfr`, `node-i.cmd` | the log, the JFR recording and the command line of server node i |
| `loader.log`, `loader.json`, `loader.cmd` | the same for the loader; `loader.json` is the raw result of the client, with the CPU of its whole machine (`machineCpuPct`) |
| `ignite-firewatch.jfc` | the JFR profile (GC, GCHeapSummary, CPULoad 1 s, ThreadAllocationStatistics 1 s, ExecutionSample 10 ms) |
| `profile.json` | hot methods of the measurement window from `jdk.ExecutionSample` (summed over the nodes): `self` is the method on top of the stack, `total` anywhere in the stack, 200 entries each |
| `work/` | Ignite work directories, temp and JFR repositories of the child JVMs |

### `result.json`

- `status` (`ok`/`failed`), `error`, `harnessVersion`, `scenarioName`, `scenarioHash`, `runId`, `phase`,
  `igniteVersion` (from `ignite.version()`), `startedAt`, `measureStartAt`, `warmupSec`, `measureSec`, `wallSec`,
  `connectMs`, `preloadMs`, `errors` (failed operations).
- When more than 1 % of the load operations fail (warmup included), the phase fails with
  `N of M operations failed (P%), the first: …`: such a run measures a breakage, not speed. The loader puts the first
  error into `loader.json` (`firstError`).
- The launcher takes `startMs` and the version of a node from its `HARNESS_READY` line and reads the node log line by
  line up to it, so a log of hundreds of MB does not get in its way.
- `throughput`: ops/s over the per-second samples of the measurement window: `mean`, `stdev`, `min`, `max`, `total`,
  `samples`.
- `latencyUs`: `mean`, `p50`, `p90`, `p99`, `p999`, `max` (microseconds, an HdrHistogram over the whole measurement
  window; at a fixed rate latency counts from the moment the operation was scheduled).
- `gc`: over the measurement window, summed over the servers: `count`, `totalPauseMs`, `maxPauseMs`, `pausePct`,
  `allocMbPerSec`.
- `cpu`: percent of all the CPUs of the machine: `serverJvmPct` (summed over the servers, user + system), `machinePct`,
  `loaderJvmPct`.
- `heap`: `afterGcMbMean` (summed over the servers), `committedMbMax`.
- `nodes[]`: the same for every node plus the file names; `env`: JDK, OS, number of CPUs, memory of the machine.
- `noise[]`: what else ran on every host during the load: `foreignCpuPct`, the CPU of the machine over the measurement
  window that none of the JVMs of the phase used (the machine CPU comes from the JFR of its nodes, or from the loader on a
  host of its own); `stealPct` and `throttled`, from `/proc/stat` and the thermal throttle counters read right before and
  right after the loader (`HostProbe counters`, over ssh on a remote host); null where a host does not tell. `noisy[]`
  says why the numbers may be off: foreign CPU of 10 % or more, steal of 1 % or more, any throttling.

### `series.json`

`t[]`, `ops[]`, `p50Us[]`, `p99Us[]`, `loaderCpuPct[]` (the client) and `serverCpuPct[]`, `machineCpuPct[]`,
`gcPauseMs[]`, `allocMb[]`, `heapUsedMb[]` (from JFR, `null` where there is no sample).

## Scenario

A scenario is a class in the package `org.apache.ignite.firewatch.scenarios`
(`modules/harness/src/main/java/org/apache/ignite/firewatch/scenarios/`) that extends
`org.apache.ignite.firewatch.harness.api.Scenario` and carries `@Bench`. The harness finds such classes by itself
(`Catalog`): adding a scenario is adding a file, removing one is removing the file.

```java
@Bench(id = "putget-thin-t16", version = 1,
    description = "Main tracker: thin client, 16 threads, 1 in-memory server, 100k keys x 100 B, 70% get, closed loop",
    loader = @Loader(client = Client.THIN, threads = 16, heapMb = 192))
public class PutGetThinT16 extends PutGetInMemory {
}
```

- `@Bench`: `id` (the stable identity, jobs refer to it), `version` (raise it when what is measured changes: the hash of
  the scenario is `sha256(id:version)`), `description`, `@Servers(count, heapMb, jvmOpts, group)`,
  `@Loader(client, threads, count, heapMb, jvmOpts, rateOpsPerSec, group)`, `warmupSec`, `measureSec`,
  `startTimeoutSec`, `setupTimeoutSec`. These are plain data: the catalog reads them without Ignite on the classpath.
- The methods of the class run in the JVMs of the nodes and the loader, which have the jars of the commit:
  `configureServer(IgniteConfiguration, Env)` sets only what differs from the defaults of Ignite (discovery, the client
  connector, the local host and the work directory are set by the harness); `configureClient` / `configureThinClient`
  for the loader; `setup(Session)` makes the caches, calls `session.activate()` for persistence and loads the data (its
  duration is the preload metric); `op(Session, ThreadLocalRandom)` is one operation, called from every load thread, and
  its latency is what gets recorded.
- `Env.resource(name)` copies a resource of `harness.jar` (for example `ssl/node.jks`) into the work directory of the
  node.
- `rateOpsPerSec = 0` is a closed loop without pauses; above zero it is an open loop, the rate split between the threads.
- The heap is set as `-Xms` = `-Xmx`; `jvmOpts` always get the `--add-opens` of JDK 17, `-DIGNITE_QUIET=true`,
  `-DIGNITE_UPDATE_NOTIFIER=false`, `-Djava.net.preferIPv4Stack=true`, `-Djava.io.tmpdir` inside `--out` and, for the
  servers, `-XX:StartFlightRecording` with `ignite-firewatch.jfc`.
- Scenarios are compiled against the `ignite-core` release and run with the jars of the commit: use public API that has
  been stable for long, or the phase fails on old commits with a clear error.
- Host groups (`group`) are not used yet, there is only `local`; `@Loader(count)` above 1 is not supported.
- Sizes of the stand: `Scale.current()` gives the region (`regionMb(base, sql)`) and the number of entries
  (`entries(base, bytes per entry, copies, sql)`). Without `--scale` these are the same base numbers, so the history
  does not break. A new scenario takes its sizes from here, not from constants.

## Plugins

A plugin is a jar of scenarios of its own that run Ignite together with an extension of another repository
(ignite-extensions, say); `examples/spring-data` is one. The jar has its scenario classes (`@Bench`, in any package),
their sources as resources (the UI shows them), and `META-INF/firewatch-plugin.properties`:

```properties
name=spring-data                                   # lowercase letters, digits, '-': build keys and URLs carry it
title=Spring Data
repo=https://github.com/apache/ignite-extensions.git
branch=master
module=modules/spring-data-ext/spring-data          # what the stand builds next to Ignite, as mvn -pl takes it
package=org.apache.ignite.firewatch.examples.springdata   # optional: where the scenarios are, not the whole jar
```

Everything else is `provided`: the harness API, Ignite and the extension with what it leaves `provided` (Spring for
spring-data-ext) come from the build of the pair under test. A library of the scenarios themselves is shaded into the
jar with a relocation: the jar comes before the build on the classpath.

`list --plugin JAR` prints `{plugin, scenarios}`; `run --plugin JAR` runs one of its scenarios, whose id is looked up
among those of the plugin only, with the jar on the classpath of every JVM: `harness.jar:JAR:LIBS/*`. On a remote stand
the jar goes to `<workDir>/plugins/` of every host. The hash of a scenario of a plugin is
`sha256(plugin/id:version)`.

## Remote stand

With `--stand FILE` the JVMs of a phase run on Linux hosts; the launcher stays on this machine and drives them over ssh.
There are no agents on the hosts. A host needs JDK 17, login by key, `rsync` and `pkill`; no sudo.

```json
{"hosts": [{"address": "bench-1", "user": "bench", "port": 22, "role": "servers", "javaHome": "/usr/lib/jvm/java-17",
            "workDir": "/opt/firewatch", "dataAddress": "10.9.0.1"},
           {"address": "bench-2", "user": "bench", "port": 22, "role": "loaders", "javaHome": "/usr/lib/jvm/java-17",
            "workDir": "/opt/firewatch", "dataAddress": null}],
 "sshKey": "", "sshKnownHosts": "", "sshAcceptNewHosts": false,
 "fingerprints": {"bench-1": {"cpu": "…", "cpus": "16", "memGb": "32", "kernel": "Linux 6.8.0", "jdk": "17.0.10+7"}},
 "runPrefix": "598ae77e-"}
```

- The placement is fixed: node `i` goes to `servers[i % N]` in the order of the file, the loader to the first host of
  `loaders`. Ignite listens on `dataAddress` when it is set; ssh always goes to `address`.
- Before a phase `rsync` puts `harness.jar` into `<workDir>/harness.jar` and the jars of the build into
  `<workDir>/builds/<build key>/libs`: they stay on the host, and a repeated phase copies nothing. The directory of the
  phase is `<workDir>/runs/<run-id>`.
- ssh asks nothing (`BatchMode=yes`) and does not log in to a host whose key `known_hosts` lacks;
  `sshAcceptNewHosts: true` takes such a key on first contact and adds it (`StrictHostKeyChecking=accept-new`), a changed
  key is refused all the same.
- ssh keeps the connection for the whole life of the JVM and brings its output into the local log, so `HARNESS_READY`,
  the logs and the exit code work as they do locally.
- Every JVM has its own marker `-Dignite.firewatch.run=<run-id>-<label>`: signals go by a separate ssh call with
  `pkill -f`, and exactly one JVM stops.
- After the stop `rsync` brings back `node-i.jfr` and `loader.json`, the results are computed on this machine, and the
  directory of the phase on the host is removed, after a failure too. `result.json` gets `nodes[].host`, `loaderHost`
  and `hosts`: what each host said about itself before the phase.
- At the start of a phase whatever this stand (`runPrefix`) left on the host is taken off it; the host keeps the 20
  latest builds. `HostProbe`, run there on the JDK of the stand, tells about the machine; when it differs from
  `fingerprints`, the phase fails, saying what changed.
- `check` puts `harness.jar` on the host and runs `HostProbe`: the facts of the machine (with a second of watching its
  CPU for steal time and foreign load), then it opens the Ignite ports on each host in turn and tries them from the
  others. The same connection compares the clocks: the server answers with its time, the time on the wire is taken out.
