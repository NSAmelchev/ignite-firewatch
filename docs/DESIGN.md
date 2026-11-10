<!-- Stand design as of 2026-10-31. Make changes here. -->

# Firewatch — stand design

Firewatch tracks the performance of Apache Ignite master: it builds commits, runs synthetic load on 1–3 nodes,
compares versions with the ABBA scheme and shows the result in its own UI. It finds where a metric shifted and narrows the search down to the commit.
Comparisons are not limited to branch commits: you can compare a PR against master, or any two refs, for example `2.17.0` and `2.18.0`.

This document describes how the stand works and why. Details live in other files: [README](../README.md) and [DEVELOPMENT.md](../DEVELOPMENT.md) (running, building,
deployment), and the module READMEs (in Russian): [modules/controller/README.md](../modules/controller/README.md) (jobs, statistics, API, UI) and
[modules/harness/README.md](../modules/harness/README.md) (phase, result format, scenarios).

## Principles

- **Nothing disturbs a measurement.** One worker: a build and a measurement never run at the same time, and neither do two phases. Each phase runs on fresh JVMs.
- **Comparisons matter, not absolute numbers.** The stand is small and noisy, so the verdict relies on ABBA within a job and on the A/A noise.
- **A human starts heavy work.** Auto-run and schedules are off by default; a bisect starts only from a button.
- **Load code does not depend on the commit.** One harness serves all versions; only the Ignite jars on the classpath change.
- **State is computed, not accumulated.** A comparison's status and counters are derived from its jobs; tables store only what was specified.
- **A new entity is made of ordinary jobs.** Comparisons and bisects have no executor of their own: their steps are the same ABBA jobs in the same queue.

## Stack and why

| Decision | Why |
|---|---|
| Harness on Java 17 and the Ignite public API | The API has been stable for years. Yardstick left the main repository and is heavy to build; JMH does not provide cluster load |
| The harness compiles against a release (`ignite.version`, 2.18.0) and runs with the commit's jars | one load code for all commits; a breaking commit gives a clear phase error, not a controller crash |
| The controller is in Java too | one toolchain and one image |
| HTTP on `jdk.httpserver` with a small `Router`, Jackson, SQLite (`sqlite-jdbc`), git via CLI | the controller fits in `-Xmx128m`, the memory goes to Maven and the benchmark JVMs |
| UI: static files without a build step, ES modules, vendored uPlot | no separate frontend toolchain |
| Scenarios as Java classes with `@Bench` | a scenario needs code (caches, transactions, SQL), not just parameters; the Scenarios page shows the source of what runs |
| Docker: one image, one `docker/docker-compose.yml`, external volumes | Maven and the JDK ship with the stand; `compose down -v` cannot wipe the results |
| No authentication | the stand runs on an internal network and is not exposed outside |

## Environment

| What | Value |
|---|---|
| Host | a machine or VM with Docker, sized for 4 vCPU and 5 GiB |
| Container | `ignite-firewatch`, `mem_limit 4500m` without swap, port 8090 exposed, `init: true` |
| Data | volumes `ignite-firewatch-data` (`/data`, valuable) and `ignite-firewatch-cache` (`/cache`, safe to wipe) |
| Ignite | master `2.19.0-SNAPSHOT`, the build requires JDK 17; squash commits only, about three a day on average |

Deployment: `docker/deploy.sh` rsyncs to the host and builds the image there with `docker compose up -d --build`. The address, Docker context and
time zone of your stand live in the uncommitted `docker/deploy.env`. Without a container, the stand starts with `bin/firewatch.sh`.
In both cases the application settings are in `config/ignite-firewatch.properties`.
One instance is one stand: its own DB, its own port, and its own prefix for its JVMs and phase directories, so restarting one instance does not kill
the jobs of another on the same machine or the same host. The prefix depends on a random value in the DB: the data directory is the same in every container. During a deploy the stand goes into maintenance: the worker finishes the current job and takes no new ones; the `worker.paused` flag lives in the DB and
survives a restart.

## Architecture

```
 Watcher            Calibrator         UI / API            Comparisons, Bisector
 new commits        schedules          manual runs         comparisons and search steps
      └──────────────────┴────────┬────────┴──────────────────────┘
                                  ▼
                     JobService → queue in SQLite
                                  │  one worker
                                  ▼
                              JobRunner
             ┌────────────────────┼─────────────────────┐
             ▼                    ▼                     ▼
          Builder          harness.jar run            Stats
        commit jars      phases A1 B1 B2 A2   Δ, threshold, verdict

 harness.jar run (launcher without Ignite on the classpath)
   ├─ ServerMain × N   nodes, separate JVMs: harness.jar + commit jars, JFR
   └─ LoaderMain       client: setup, load, HdrHistogram
```

### Controller

| Part | What it does |
|---|---|
| `GitMirror` | bare mirror: branches and tags on every poll, `pr/N` on demand; the URL and the branch can change at runtime |
| `Watcher` | polls every `watch.pollSec`, turns new commits after the watermark into jobs, subject to the path filter and the queue limit; calls `Calibrator` on the same tick |
| `Calibrator`, `Freshness` | keep A/A and master vs release fresh: a stale result waits for the stand to be idle, an overdue one runs regardless of the queue. Auto-run segments follow the same rule |
| `Builder` | builds `ignite-core` with its upstream modules, lays out the runtime classpath in `artifacts/<key>/libs`; scenarios with `@Bench(modules)` get a separate build keyed `<sha>+<modules>` |
| `JobService` | enqueues jobs, resolves refs, deduplicates, picks the release, serves data for Commits and trends, estimates time |
| `JobRunner` | the single worker: build, phases, comparison; timeouts, cancellation, cleanup of JVMs by the `-Dignite.firewatch.run` marker |
| `Stats`, `ProfileDiff` | ABBA verdicts and the A/B hot-method diff |
| `Comparisons` | two refs and a set of scenarios, one ABBA job per scenario |
| `Bisector` | searches for the commits that shifted a metric: a fast search and, from a button, a deep search for shifts near the noise floor; steps are ordinary jobs, the state is JSON in the `bisects` row |
| `Attention` | checks master vs release against what was found on the branch |
| `Retention` | keeps JFR files and phase work directories only for the last N jobs, trims logs over 20 MB to their head and tail; `Comparisons` and `Bisector` apply the comparison and bisect limits, `Builder` applies the build LRU |
| `Notifier` | Telegram and webhook notifications about regressions and finished work |
| `Stand`, `StandService` | what the stand runs on: this machine or hosts over ssh, scenario sizes; Check, recommendations, host cleanup; frozen by the first completed phase |
| `Api`, `Router`, `Settings` | REST, UI static files, settings with validation |

SQLite: `commits`, `builds`, `jobs`, `runs`, `bisects`, `comparisons`, `settings`, the schema version in `schema_version` (currently 3).
Run files: `data/runs/<job>/<phase>/`. Cache: `mirror`, `m2`, `artifacts`, `work`.

### Harness

The launcher (`PhaseRunner`) starts the servers, then the loader, stops the nodes with SIGTERM, parses JFR and writes `result.json`, `series.json`,
`profile.json`. `Catalog` finds classes with `@Bench` in the jar; `harness.jar list` gives the catalog to the controller at startup.

`Placement` decides where each JVM runs, `Host` starts it: on this machine or over ssh through `RemoteHost`. There are no agents on the hosts: the launcher
stays next to the controller, ssh holds the connection and carries the JVM output to a local log, files move via rsync. The placement does not change from
run to run, to avoid adding noise. `HostProbe` runs on the host itself and on its JDK, so Check does not depend on host utilities and checks
the JDK right away; `StandCheck` combines its answers into a report and uses the same TCP connection to measure the link and the clocks between hosts.

`Scale` holds the stand sizes. Without them a scenario produces exactly its own numbers and the same command line, so the history of stands without sizes does not break.

## Measurement

**Phase** is one version on fresh JVMs: server start, `setup` (caches and preload; this is the preload metric), warmup, measurement, graceful shutdown.
By default, 30 s of warmup and 90 s of measurement. JVM flags are set by the scenario and are the same for both sides: heap `-Xms=-Xmx`, G1, `AlwaysPreTouch`,
`--add-opens` for JDK 17, JFR with `ignite-firewatch.jfc`.

**ABBA** is the phases A1 B1 B2 A2, about 8–9 minutes per job when the builds are ready. A = mean(A1, A2), B = mean(B1, B2), Δ = B/A − 1.

**A/A** is the same scheme on one sha. A/A runs give the noise threshold: p95 of |Δ| over the last 10 calibrations of the scenario, not lower than the metric default.

**A metric verdict** requires |Δ| above the threshold (the larger of the A/A noise and the spread between repeats of one side), and, for metrics with per-second
series, also a 95% bootstrap CI that excludes zero. The job verdict is based on throughput, p99 and server CPU per operation; allocations, GC and the rest get
their own verdicts. Completed ABBA jobs of the same pair are merged when the scenario hash is the same: `sha256(id:version)`.

Metric sources: throughput and latency on the client via HdrHistogram; GC, allocations, CPU and heap of the servers from JFR; hot methods from
`jdk.ExecutionSample`.

## Entities

**Job** (`single`, `abba` or the internal `probe`) is the unit of the queue. Source: `manual`, `auto`, `calibration`, `compare`, `release`, `bisect`, `probe`. Higher priority
runs earlier; a job with the same sha, type and scenario is not duplicated while it is active. A controller restart moves the running job to
`interrupted`; a bisect step is enqueued again in that case.

**Branch history** is ABBA "commit or segment vs its parent". Auto-run measures by segment or by commit, Split divides a segment manually,
Timeline and Commits show the most precise run. `compare` runs do not go into the history, repeats or thresholds.

**Comparison** is the question "A vs B on these scenarios". Refs are resolved once; all scenarios measure the same two commits.

**Bisect** follows one metric into every part of a segment that shifts it on its own, the largest first, and narrows each one down to a
commit. Completed pairs are reused; the queue holds at most one bisect step. Outcome: `found`, `spread` or `lost`; a failed step gives
`stopped` with Resume. A bisect belongs to a comparison through the `comparison_id` of its root job; there is no separate link. One step takes about
9 minutes, a search takes about 2.5 hours, and several bisects interleave their steps.

**Reconciliation with findings** (the badges in the dashboard matrix) takes the latest master vs release and subtracts what was found on the branch. If the sum of the commits' Δ matches the master Δ, the metric
is `explained`. If it does not, `partial`. Without findings, the status comes from the latest bisect, and if there is none, the metric is `unexplored`. A bisect measures all
metrics, so a segment that shifted another metric gives `narrowed`, and the search starts from it.

**Release** is what the dashboard compares master with: empty `release.tag` (the highest X.Y.Z tag), a mask `2.18.*`, a tag, a sha or a branch.

## Scenarios

There are sixteen: put/get (thin and thick client, persistence, TLS, FULL_SYNC on two servers), transactions on two and three servers, SQL in pairs of
H2 and Calcite on one and two servers. Adding a scenario means adding a file: the harness finds the classes on its own. `id` never changes, the history
depends on it; `version` is bumped when what is measured changes, and a new series starts; `title` can change freely.

## Build

The commit is exported from the mirror into `work`, then `mvn -pl modules/core -am install` runs without tests, javadoc and checkstyle, followed by
`dependency:copy-dependencies`. A finished build is stored by key and reused. Core builds in about 40 seconds on a warm `m2`,
16 MB of jars. The SQL build with `indexing` and `calcite` takes ~55 MB; test-scope dependencies missing from the local repository are replaced
with empty jars, otherwise Maven cannot resolve them. A failed build does not block the queue. The `IGNITE_FIREWATCH_FAKE_BUILD=1` mode puts release jars
in place of a build: this is how the queue and the UI are debugged.

## UI

Only Queue and the pages with work in progress refresh themselves: job, bisect, comparisons. The other pages load on navigation.

| Page | Question it answers |
|---|---|
| Dashboard | is master all right: stand state, the master vs release matrix with search status, Result |
| Timeline | how segments cover the branch since the release and where a search is running |
| Commits, Commit | what has been measured per commit and how runs of one commit differ |
| Compare, Comparison | how two refs or a PR differ, which commits are responsible |
| Bisects, Bisect | which searches are running and how they ended, details of one search |
| Queue | what is running, what is waiting, when it will finish |
| Job | details of one job |
| Noise | what the noise thresholds are now and which calibrations they come from |
| Scenarios | what exactly runs, with the source code |
| Settings | automation, release, notifications, limits, stand, system, repository |
| Setup | where to run a new stand and what to measure: shown once, on an empty DB |

## Memory

| Mode | What is used |
|---|---|
| Idle | controller ≈ 0.2 GB |
| Build | Maven `-Xmx1536m`, container peak 1.6–1.9 GB |
| 1 server | heap 512m, data region 256m, loader 256m, launcher 128m; ≈ 1.4 GB |
| 2 servers | per node: heap 384m and data region 128m |
| 3 servers | per node: heap 512m and data region 256m |

## Testing

- `mvn test`: statistics, SQLite, the git mirror on a temporary repository, the end-to-end pipeline with `FAKE_BUILD` and a script instead of the harness.
- The UI smoke test renders every `app.js` page against the real API. Without `node` or permission to listen on a port, it is skipped.
- Changes of mode and anything destructive are tried on a local stand with `IGNITE_FIREWATCH_FAKE_BUILD=1`; the production stand is only read.

## Risks and limitations

- **Noisy neighbors.** Unrelated load on the host once dropped all phases by 28%. ABBA and the thresholds protect against it.
- **p99 at 4 threads is noisy**: on `putget-thick-t4`, throughput and CPU per operation are reliable.
- **Weak signal.** When Δ is less than 3 phase spreads, a bisect rarely reaches the commit: the dialog warns about it and does not preselect such metrics.
- **A gradual decline** made of pieces below the noise floor does not break down into commits; the bisect ends as `spread`.
- **Compatibility.** The harness works only through the stable public API; on old commits a scenario can fail with a phase error.
- **A single machine does not measure a real network.** The nodes are on loopback. Remote hosts exist for that: a new stand is a separate instance with
  its own DB, and runs of different stands do not mix.
- **Remote mode is tested only on VMs.** Three Linux VMs on one machine passed the whole list, but there was no real network latency and no different hardware.
- **The stand is frozen completely.** A kernel or JDK update on the host stops jobs with a clear reason, and the only way out is a new instance. This is intentional:
  such runs cannot be compared with the old ones.
- **One loader.** Multiple loader JVMs on multiple hosts are waiting to be tested on real machines.
