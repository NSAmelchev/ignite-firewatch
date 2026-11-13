# controller — queue, commit builds, ABBA statistics, API and UI

One JVM process (`controller.jar`, `-Xmx128m` is enough) that:

- keeps a bare mirror of `apache/ignite` (`GitMirror`, the git CLI; branches and tags on every poll, `pr/N` on request);
- follows the branch (`Watcher`): new commits after the watermark become jobs by the auto-run policy;
- builds `ignite-core` of a commit with its upstream modules (`Builder`) and puts the runtime classpath into
  `cache/artifacts/<sha>/libs`; a scenario with `@Bench(modules)` (SQL) gets a build of its own with those modules in
  `cache/artifacts/<sha>+<modules>/libs`;
- runs the jobs with one worker (`JobRunner`): build → harness phases → statistics; it never builds while measuring;
- computes the ABBA verdict (`Stats`): Δ%, bootstrap CI, a noise threshold from A/A calibrations;
- serves the REST API and the static UI (`Api`, `jdk.httpserver`, no frameworks).

Storage is SQLite (`data/ignite-firewatch.db`, WAL) plus the run files `data/runs/<job>/<phase>/` (result.json,
series.json, logs, JFR).

## Run locally

```bash
mvn -q package                     # modules/harness/target/harness.jar + modules/controller/target/controller.jar
modules/harness/dev-libs.sh                # the ignite-core release for FAKE_BUILD (and dev-libs-sql: + indexing, calcite)
IGNITE_FIREWATCH_FAKE_BUILD=1 IGNITE_FIREWATCH_PORT=8085 IGNITE_FIREWATCH_DATA_DIR=/tmp/bench/data IGNITE_FIREWATCH_CACHE_DIR=/tmp/bench/cache \
  java -Xmx128m -jar modules/controller/target/controller.jar   # UI at http://localhost:8085
```

On an empty DB the UI opens with the setup wizard: first the application (the repository, the release and the check of
the builds on this machine), then the hosts and the sizes of the scenarios. Until the wizard is done, `Watcher` waits and
clones nothing. Then the controller makes a full clone of `apache/ignite` (≈0.5 GB, about 2 minutes), sets the watermark
to the current head and runs nothing while auto-run is off (it is off by default). `FAKE_BUILD=1` replaces the build with
a copy of the release jars, to debug the queue and the UI without Maven; the harness stays real.

## Configuration (`config/ignite-firewatch.properties`, env `IGNITE_FIREWATCH_*` or `-Dignite.firewatch.*`)

The settings live in `config/ignite-firewatch.properties` (in the working directory; `IGNITE_FIREWATCH_CONFIG` names
another file) as lines like `data.dir = …`. The environment variable `IGNITE_FIREWATCH_DATA_DIR` overrides the line of the
file, the system property `-Dignite.firewatch.data.dir` overrides both. In the container `config/` is mounted read-only,
and the image sets the paths and the port.

| Variable | Default | What |
|---|---|---|
| `IGNITE_FIREWATCH_DATA_DIR` | `data` | SQLite and the run results (valuable) |
| `IGNITE_FIREWATCH_CACHE_DIR` | `cache` | the mirror, `m2`, artifacts, build work directories (can be wiped) |
| `IGNITE_FIREWATCH_HARNESS_JAR` | `modules/harness/target/harness.jar` | the harness |
| `IGNITE_FIREWATCH_BIND_HOST`, `IGNITE_FIREWATCH_PORT` | `0.0.0.0`, `8090` (8080 in the container) | HTTP |
| `IGNITE_FIREWATCH_REPO_URL`, `IGNITE_FIREWATCH_BRANCH` | `https://github.com/apache/ignite.git`, `master` | what is mirrored and followed; instead of a URL, a path to a git repository or bundle on this machine works (a stand without network) |
| `IGNITE_FIREWATCH_JAVA`, `IGNITE_FIREWATCH_MVN` | java of the current JVM, `mvn` | executables for the child processes |
| `IGNITE_FIREWATCH_MAVEN_HEAP_MB` | `1536` | `-Xmx` of Maven when it builds a commit |
| `IGNITE_FIREWATCH_MAVEN_SETTINGS` | empty | settings.xml for every build (`-s`): mirrors, proxies, credentials; a relative path starts in the directory of the application |
| `IGNITE_FIREWATCH_MAVEN_ARGS` | empty | more arguments of every mvn call, space-separated, after the application's own (for example `-o`, `-gs FILE`, `-Dkey=value`) |
| `IGNITE_FIREWATCH_BUILD_TIMEOUT_MIN`, `IGNITE_FIREWATCH_FETCH_TIMEOUT_MIN` | `25`, `30` | timeouts of a build and of `git fetch` |
| `IGNITE_FIREWATCH_HARNESS_HEAP_MB` | `128` | heap of the harness launcher JVM (nodes and loader follow the scenario) |
| `IGNITE_FIREWATCH_PHASE_GRACE_SEC` | `120` | margin over the harness's own worst case, after which a phase counts as hung |
| `IGNITE_FIREWATCH_FAKE_BUILD`, `IGNITE_FIREWATCH_FAKE_LIBS_DIR` | `0`, `modules/harness/target/dev-libs` | development mode |
| `IGNITE_FIREWATCH_SSH_KEY`, `IGNITE_FIREWATCH_SSH_KNOWN_HOSTS` | empty: what ssh finds by itself; `/ssh/key` and `/ssh/known_hosts` in the container | key and `known_hosts` of a remote stand, never stored in the DB |
| `IGNITE_FIREWATCH_SSH_ACCEPT_NEW_HOSTS` | `false` | `true`: ssh takes the key of a host missing from `known_hosts` on first contact and adds it there (`StrictHostKeyChecking=accept-new`); a changed key is refused all the same |

Settings changed from the UI (the `settings` table, seeded on the first start):
`autorun.enabled` (false), `autorun.mode` (`abba-vs-parent` | `single`), `autorun.scenario` (one or more scenarios,
comma-separated, a job for each; the first is the default scenario of the pages and the API; `putget-thin-t16,putget` on
the stand), `autorun.pathFilter` (globs: `modules/core/src/main/**`, …, `pom.xml`, `**/pom.xml`), `autorun.maxBacklog` (5),
`autorun.keepDays` (1), `watch.pollSec` (3600), `watch.watermark`, `retention.keepJfrJobs` (30), `retention.artifactsGb` (3),
`calibration.*` and `master.*` (A/A and master vs the release: `mode` `off` | `keep`, `keepDays` (7), `scenarios`; off by
default), `notify.*` (see below).

## Stand (`Stand`)

One instance of the application is one stand. `local`: every JVM on this machine. `remote`: the JVMs on Linux hosts over
ssh; a host has an address, a user, an ssh port, a role (`servers` or `loaders`), JDK 17, a work directory and, optionally,
a data address for the traffic between nodes. At least one host of each role is needed, and an address cannot appear
twice. The values go into the ssh command line, so they are checked strictly. On `remote` the controller puts
`stand.json` into the directory of the phase and gives it to the harness (`--stand`); from there the harness works, see
[its README](../harness/README.md#remote-stand). Commits are still built by the controller.

**The check of the builds** (`POST /api/app/check {url, branch}`, the class `BuildCheck`) looks at the machine of the
controller, where the commits are built: whether the repository reads (`git ls-remote` of the address and the branch of
the form, saved or not), `java` of the configuration (version 17 or newer, and its `java.home`), whether `javac` is next to
it (a JRE will not do: Maven compiles with the Java it runs on), `mvn -v` with exactly the environment of a build
(`JAVA_HOME` from `java`, `MAVEN_OPTS`), git, and the space under `cache.dir` (and `data.dir` when it is on another disk):
less than 5 GB is an error, less than 20 GB a warning. When Maven runs on another Java than `java`, it is an error below 17
and a warning otherwise; the hint names the `JAVA_HOME=` line of `/usr/local/etc/mavenrc`, `/etc/mavenrc`, `~/.mavenrc` or
`/etc/java/maven.conf` that the `mvn` script reads after the environment it was given. The last report is kept
(`GET /api/app/check`), a new run replaces it. The commands only read: the wizard runs the check when it opens and when the
repository changes, Settings → Application shows the kept one and runs it by itself only when there is none yet; the
answer holds `items` (`ok`/`warn`/`fail`, the text and `fix`, how to fix it) and the log of the commands in the format of
Check. With `FAKE_BUILD` a single warning stands for Maven.

**Check** (`POST /api/stand/check`, the controller calls `harness.jar check`) checks the saved stand or the one just
entered. It fails on ssh, a JDK below 17, less than 5 GB of disk, less than 2 GB of memory, foreign JVMs, Ignite ports
unreachable between the hosts and clocks a second apart; it warns about clocks 100 ms apart, swap, THP `always`, a CPU
governor other than `performance`, turbo boost on, steal time of 1 % or more (a VM whose neighbours take its CPU) and a CPU
busy with something else for 10 % or more during the second of the probe (with the three busiest processes). Warnings and
errors carry `fix`, how to fix them. On `local` a foreign JVM is only a warning: it may be the development environment.
Check takes the Ignite ports, so it runs with an empty queue and one at a time: a job queued meanwhile waits for its end.
A passed Check of a stand that is not fixed yet keeps the fingerprint of each host (CPU, cores, memory in GB, OS kernel,
JDK), and without it a job on `remote` does not start. Before every phase the harness compares the host with its
fingerprint: when they differ, the phase fails, saying what changed, and nothing goes into the history. Fixing the stand
fixes the fingerprint too: a Check after it only shows the state.

**Sizes** (`sizes`): memory of a node, how full the data region is, and a thread factor. Without the memory the scenarios
take the numbers of their code, and the command line of a phase does not change: that is how every stand set up earlier
works. With the memory the harness splits it into a reserve (10 %, at least 256 MB), the heap (35 % of the rest, 50 % for
SQL, at least 256 MB) and the region, and derives the number of keys and rows from the region. Check suggests sizes: the
memory of the weakest server host for the biggest scenario, threads by the CPUs of the loader. When the memory of a node,
entered or suggested, does not fit such a host, the report warns about it (`sizesWarning`). The report also names the
hosts it checked (`checked`): the wizard does not take it after a host is edited. The preload timeout grows with the volume.

**Measure load** (`POST /api/stand/measure`) queues a job of type `probe`: the main scenario on the head of the branch
with ×1, ×2 and ×4 threads, 15 s of warmup and 45 s of measurement each, while the throughput grows by at least 10 %. The
outcome is kept in `stand.load`, and the page offers to apply it. Such jobs stay out of the history of the branch and do
not fix the stand. Once the stand is fixed, there is no button.

**Cleanup.** The harness cleans up after itself, on SIGTERM too. When it is killed hard, JVMs stay on the host: the next
phase takes them off, so does the controller at start and after a job that did not end `done`, and so does the Clean hosts
button (`POST /api/stand/sweep`). The run marker and the directory of a phase start with the prefix of the stand, so the
JVMs and phases of another stand on the same host are left alone. The prefix comes from the data directory and
`stand.instance`, a random value chosen once and kept in the DB: every container has the same data directory, `/data`.
The cleanup at start runs in the worker thread before the first job, so a silent host delays the queue, not the start of
the application.

The choice lives in `stand.*`, only `PUT /api/stand` writes it, `PUT /api/settings` cannot change it. The first finished
phase fixes the stand: results of different stands must not mix, another stand is another instance with its own data
directory. A failed phase does not fix it, so wrong hosts can be corrected. A stand with jobs from earlier versions counts
as `local`, and it is not shown the wizard.

## Jobs

- `single` is one phase `S` for ref A. `abba` is the phases `A1 B1 B2 A2`: A is the baseline, B the target. `probe` is
  Measure load, the phases `x1 x2 x4`.
- Source: `manual` (UI/API), `auto` (the watcher), `calibration` (A/A: the same sha on both sides), `compare` (the
  Compare tab: manual debugging, stays out of the history of the branch), `release` (master against the release tag),
  `bisect` (a step of a bisection: an ordinary segment or a commit against its parent, in the history of the branch like
  any other).
- Refs are resolved to shas when queued (`master`, `master~3`, a sha or its prefix, a tag `2.17.0`, `pr/13706`, GitHub
  links `…/pull/N`, `…/pull/N/commits/SHA`, `…/commit/SHA`, `…/tree/BRANCH`, `…/releases/tag/TAG`); a job with the same
  shas, type and scenario is not duplicated while it is queued or running.
- Priority: higher runs first; auto = 0, manual 1 by default. Statuses: `queued → running → done | failed | cancelled |
  interrupted` (the last one is a restart of the controller during the job).
- Auto-run: the watermark starts at the head of the first poll and only moves forward; commits that came during a pause,
  were filtered out by the paths or did not fit `maxBacklog` stay available for a manual run on the Commits page (the
  Run button is ABBA against the parent).
- Cancel kills the current process and all its descendants (`ProcessHandle.descendants`) and finishes off the JVMs by the
  marker `-Dignite.firewatch.run=<stand>-job<id>-…`, where `<stand>` is a hash of the path `IGNITE_FIREWATCH_DATA_DIR`. At
  start the controller turns `running` into `interrupted`, unfinished builds into `failed`, and kills the JVMs with the
  marker of its own stand; it leaves alone those of others on the same machine.
- Build artifacts are evicted LRU above `retention.artifactsGb`, except those active jobs need.
- After every job a run log over 20 MB is cut to its first and last 5 MB with a note in the middle. A node that logs every
  failed request writes hundreds of MB in a phase. The API streams the run files, and the tail of a job log is read from
  its end.
- When the harness dies without writing `result.json`, the error of the phase takes the last exception of `harness.log`
  (`the harness died: …`). "harness did not start?" stays only for a log without a single exception.

### What needs attention (`Attention`)

The matrix of the dashboard compares the last master vs release run with what was found on the branch. Found are the runs
"commit against its parent" with a verdict on the metric and the findings of bisections, so a manual Split counts too.
When the sum of their Δ matches the Δ of master within the threshold, the metric is `explained`: nothing but these commits
moved. When not, the rest shows as `partial`, also when a bisection found a commit that master no longer shows. Without
found commits the status comes from the last bisection of the metric (`running`, `stopped`, `spread`, `lost`), and when
there was none, the metric is `unexplored`. A bisection follows one metric, but every step measures all of them: when a run
of a segment moved the metric the same way as master, it is `narrowed`, the shortest such segment is shown, and the
bisection starts from it rather than from the whole range. Bisections of comparisons cover another range and do not count
here. A run of the branch newer than master vs release is a separate `new` line. The Bisect deviations button starts the
searches needed at once: a scenario is one line of the dialog with a choice of the metric, a narrowed metric starts from
its segment, a clear signal is picked in advance, a weak one only offered. A scenario may have one active bisection (of
the branch or of a comparison), so a busy scenario stands at the end of the dialog as an inactive `after #N` line, and the
same mark replaces the buttons in the details of a cell and in Still open. What needs action comes first, fully explained
scenarios are folded. `start ms` and `preload ms` stay out of it: they are the noise of a start.

### Comparisons (`Comparisons`)

A comparison is an entity of its own: two refs and a set of scenarios, with its own page `#/comparisons/N`. The refs are
resolved once when it is queued, so every scenario measures the same two commits. Every scenario is an ordinary ABBA job
with `comparison_id` and the source `compare`, at the priority chosen in the dialog (the API defaults to 2). The
`comparisons` row keeps only the question (refs, shas, label, priority); the status (`queued`, `running`, `done`, `failed`,
`cancelled`), the counters and the ETA are always computed from the jobs. A repeat of a scenario stays in the comparison
and is pooled with the earlier runs only on its page. Cancel takes off the waiting jobs and stops the running one, the
finished ones stay; Delete is there when no job is active and removes the comparison with its jobs and files. There is
one notification, when no job of the comparison is left in the queue; there is none for separate jobs.

A deviation of a comparison can be bisected: the Bisect button of a scenario row, a click on a Δ with a verdict (that
metric at once) or Bisect deviations for several scenarios together. The metrics in the dialog are sorted by the strength
of the signal: Δ divided by the spread of the phases; below 3× it is marked `weak`, such a search rarely reaches a commit.
Scenarios with a verdict are picked in advance, those where only a single metric moved are offered unticked. There is no
separate link: a bisection belongs to a comparison because its root job has `comparison_id`, and the table did not
change. The steps are ordinary bisection jobs: they are not rows of the comparison, and only the commits of the branch
itself reach Timeline and Commits. The status of a comparison does not depend on its bisections. Cancel of the comparison
takes off its active bisections too; Delete is refused while a bisection is active, and a finished bisection stays,
losing only its link to the comparison. The range is the first-parent chain from A to B (`commits` in the API answer);
when A is not an ancestor of B, as with release tags, the chain starts from their merge base, and commits on the side of A
only are not searched.

For a PR with an empty baseline: B is the trial merge of the PR into the current branch that GitHub keeps
(`refs/pull/N/merge`), A its first parent, that is the commit of the branch the PR merges into. Δ shows exactly what
merging the PR now would change. It needs an open PR without conflicts. For other refs with an empty baseline A is the
merge base with the branch, and when the target is already in the branch (the merge base would be the target itself),
its parent; any ref in the baseline sets A explicitly. Only ABBA "commit against its parent" goes into the trend and
Commits; the hash of a scenario does not include `description`, so editing the description does not break the history.

### Automatic bisection (`Bisector`)

The Bisect button of a finished segment with a verdict on a metric (Timeline, the job page, a cell of the matrix on the
dashboard); the stand never starts bisections by itself. A bisection goes into every part of the segment that moves the
chosen metric the same way on its own (the verdict of the metric), the biggest |Δ| first, and takes each down to a
commit:

- a step measures the left half of the range; a quiet one (no verdict and ≤ ⅓ of the Δ of the range) sends it on to the
  right half without measuring it (or with the measurement at hand when the pair was measured already: the decision rests
  on it, not on the estimate "Δ of the range − Δ of the left"); otherwise the right one is measured too; every half that
  moved becomes an open range; when none did, the range is measured once more (`repeat`, pooled with the earlier runs):
  if it moved, the branch ends as `spread` (several small changes), if not, as `lost`;
- on a single commit: one never measured is measured (`leaf`), with |Δ| under 2 thresholds once more (`confirm`), and the
  branch ends as `found` or `lost`;
- the outcome of a bisection is a list of findings; the status is `found` when at least one commit was found, otherwise
  `spread`; with neither, it checks whether the shift was real: the halves of the first level together give at least half
  the Δ of the segment (and when the right one was not measured, the same of a fresh run of the segment, step `root`).
  Real is `inconclusive`: the search was not precise enough; not real is `lost`: it was noise. The reason lists the found
  commits with their Δ and the share of the Δ of the segment they explain (under half: "the rest is smeared or noise");
- pairs measured already are taken as they are (`reused`); the same job in the queue is waited for, not duplicated; the
  queue holds at most one step of a bisection, at the priority it was started with (the dialogs default to 1, the API to
  2, ahead of manual jobs);
- a failed step, or one cancelled in Queue, makes the bisection `stopped`, and Resume queues the step again; a step
  interrupted by a restart is queued again by itself (`Bisector.recover()` at start); the safety limit is
  `2·(2·log₂N + 4)` steps, then `stopped`;
- Cancel: no new steps, the waiting step is taken off, the running one stops (or runs to its end), finished steps stay
  ordinary runs and are deleted when the box is ticked; a step of an active bisection cannot be deleted (`DELETE` → 400).

The state is a row of the `bisects` table (JSON `state_json`: open ranges, findings, steps with their decisions), the
steps are jobs with `bisect_id`/`bisect_step` (schema migration v2). There is one notification, when a bisection ends or
stops. A gradual decline made of pieces under the noise threshold does not break down into commits: the bisection ends it
as `spread` on the range where the halves stop moving on their own.

**Deep search** (`Bisector.deep`, the Deep search button of a bisection that ended `inconclusive`, `spread` or `lost`) is
for a shift close to the noise. One ABBA job measures with an error of the order of a phase (about 1 %), so with a shift of
2 % and a threshold of 2 % the quick search often loses the step: the half that has it does not reach the threshold, and
a quiet left half leads it to the right without measuring. The stand never starts a deep search by itself. It runs in the
same bisection, from the top of the segment, and everything measured already is used:

- on every level both halves are measured, and the decision compares them with each other (`deepChoice`) rather than with
  the threshold: a half carries the shift when it gives half the noise threshold or more and leads the other one by at
  least as much, even without a verdict;
- a close call gets more runs, up to `DEEP_RUNS` (4) per half, then the bigger half is chosen; when both carry a part,
  both are searched: these are two changes;
- a branch costs hours, so before entering it the range is measured again (`holds`): two runs are enough for a shift at
  the threshold, four closer to the noise, a branch under half the threshold closes as `lost`, a clear shift of two
  thresholds enters at once. Every branch gets its own budget of steps;
- the Δ of a pair is the median over its runs (`JobService.medianResult`); a run whose phases are more than 2.5 noise
  thresholds apart does not count, and a pair with nothing else is measured again;
- a commit has to hold half the threshold over 4 runs (two are enough for a clear shift of two thresholds), otherwise the
  branch ends as `spread` on the shortest range that still moved; two levels in a row without a half that carries the
  shift end the branch the same way.

The cost on a segment of 657 commits is about 27 jobs in a simulation (around 4 hours); a step of 2.3 % with 0.9 % of noise
is found in 96 % of cases, 1.6 % on 41 commits in 80 %; the safety limit is `6·log₂N + 8` steps over those made already. A
smooth decline without a separate step honestly ends as `spread`. A repeated ordinary bisection of the same segment will
not replace it: it takes the same measured pairs and goes the same way.

### Notifications

The settings `notify.*` (the Settings page): mode `off` | `regression` (regressions and failed auto jobs) | `all`,
Telegram (bot token and chat id; the API returns the token masked) and/or a generic webhook (`POST {"text": …}`,
compatible with Slack), the base URL for links to jobs. The "Send test" button and `POST /api/notify/test`.

### Hot methods

The harness writes `profile.json` for a phase (a cut of `jdk.ExecutionSample` of the measurement window, 10 ms).
`GET /api/jobs/{id}/profile` sums the A and B sides and returns the methods whose share of CPU samples changed most
(`self` is the top of the stack, `total` anywhere in the stack); on the job page it is a table under the charts.

## ABBA statistics (`Stats`)

For every metric A = mean(A1, A2), B = mean(B1, B2), Δ = B/A − 1. The verdict of a metric, `regression | improvement |
no-change`, needs both:

1. |Δ| > the threshold, where the threshold = max(the A/A noise of the metric, the spread between the repeats of one side
   |A1−A2|/A and |B1−B2|/B);
2. for metrics with per-second series (throughput, p99), the 95 % CI of Δ by moving-block bootstrap (5 s blocks, 2000
   iterations) does not contain 0.

A/A noise: p95 |Δ| over the last 10 calibrations of the scenario (3 or more are needed), not below the default of the
metric (throughput 2 %, p50 3 %, p99 5 %, p99.9 10 %, alloc 3 %, CPU 4 %, GC pause 15 %…). The verdict of a job rests on
throughput, p99 and "server CPU per operation" (`serverCpuUsPerOp` = CPU of the server JVMs × number of CPUs /
throughput: a growing server cost shows even when the client limits the throughput); the other metrics (GC, allocation
per operation, CPU, heap) show with verdicts of their own. All of it is kept in `jobs.comparison_json`.

**Repeats.** Finished ABBA jobs of the branch with the same A, B, scenario and scenario hash are pooled: A is the mean of
all the A phases, B of all the B phases, the CI over all the per-second series; the spread of the repeats is the maximum
within one job (the drift of the stand between days stays out of the threshold), the noise floor stays as it was. The
result is cached (LRU of 500 entries).

**Diagnostics without a verdict:** `serverStartMs` (time inside `Ignition.start()`, the slowest node), `preloadMs`.

## API

| Method and path | What |
|---|---|
| `GET /api/health` | version, worker, watcher (with `mirrorReady` and `fetch`, the progress of a running fetch), job counters, memory/disk/load, the number of live benchmark JVMs |
| `GET /api/storage` | how many bytes the runs take, their heavy files, the files of comparisons and the records of bisections: what the limits act on |
| `GET /api/log?lines=` | the last lines of the controller log |
| `GET /api/scenarios` | the scenarios of the catalog |
| `GET /api/scenarios/{name}/describe` | a scenario as `harness.jar list` lists it: id, version, topology, timings, sources |
| `GET /api/settings`, `PUT /api/settings {key: value}` | settings (validated) |
| `GET /api/stand`, `PUT /api/stand {mode, hosts, sizes}` | the stand: mode, hosts, sizes, `needsSetup`, `frozen`, the last Check and Measure load; writing it ends the wizard, after the first finished phase it is 409 |
| `POST /api/stand/check {mode, hosts, sizes}` | Check: the report of the hosts (warnings and errors carry `fix`, how to fix them) and the suggested sizes; 409 while the queue is busy or another Check runs |
| `GET /api/app/check` | the last report of the check of the builds (`at`: when it was made) or `null` |
| `POST /api/app/check {url, branch}` | the check of the builds on the machine of the controller: repository, Java, javac, Maven and its Java, git, disk; without the fields, the saved repository and branch |
| `POST /api/stand/measure` | Measure load: a `probe` job; 409 once the stand is fixed |
| `POST /api/stand/sweep` | take off the hosts what killed phases left; 409 while the queue is busy or a Check runs |
| `GET /api/commits?limit=&scenario=` | the history of the branch with the jobs of every commit (without `compare`) |
| `GET /api/commits/{sha}` | a commit, all its runs with their role (target, baseline, A/A) and the pooled repeats |
| `GET /api/jobs?filter=active|history|history-no-steps|all&limit=` | jobs; for `active` also `etaStartAt`, `etaSec` (estimated start and duration), `history-no-steps` without the steps of bisections |
| `GET /api/search?q=&limit=` | search: a job, a bisection or a comparison by number, commits by sha, PR number, ticket, words of the subject |
| `POST /api/jobs {type, refA, refB, scenario, priority, label}` | queue a job |
| `GET /api/jobs/{id}` | a job with its phases and comparison |
| `POST /api/jobs/{id}/cancel|retry|priority` | control (retry keeps the source, except `auto` → `manual`; `retry {priority?}`: without it the priority of the old job, at least a manual one) |
| `DELETE /api/jobs/{id}` | delete a finished job with its phases and the directory `runs/<id>` |
| `GET /api/jobs/{id}/log?lines=` | the tail of the current log (build or phase) |
| `GET /api/jobs/{id}/series` | the per-second series of every phase of a job in one answer (for the overlaid charts) |
| `GET /api/noise?scenario=` | the current noise thresholds by metric and the A/A calibrations they come from |
| `GET /api/runs/{id}/result|series|files|files/{name}` | the files of a phase (`.jfr` goes as an attachment) |
| `GET /api/jobs/{id}/downloads` | what a job left and how big it is: the zip without JFR and with it (`jfrBytes` = null when retention took them), the log and the jars of the build of each side |
| `GET /api/jobs/{id}/zip?jfr=0|1` | the zip of a job, streamed: the phases with their logs, results, series and profile, the logs of its builds, JFR on request; the `work/` directories of the nodes never go |
| `GET /api/trend?scenario=&limit=&refined=` | trend points: the most precise run of a commit, repeats of the same pair pooled (`runs`, `jobIds`); `refined=true` adds the segments a split refined (`refinedBy`) |
| `POST /api/jobs/{id}/split {mode: halves|commits, priority?}` | split a segment: two halves or every commit against its parent; priority 1 by default |
| `GET /api/attention` | the reconciliation with what was found: by scenario, the metrics of master vs release with their status and found commits, plus the runs newer than it |
| `POST /api/bisects {jobId, metric, priority?}` | start an automatic bisection of a segment or of a scenario of a comparison |
| `GET /api/bisects?scenario=&active=&limit=`, `GET /api/bisects/{id}` | bisections (newest first) and one with its steps, waiting job and estimate; one also has `positions` (the ends of the ranges in commits from the start of the segment) and `commits` for the search map |
| `POST /api/bisects/{id}/cancel {stopRunning, deleteFinished}`, `POST /api/bisects/{id}/resume` | cancel / continue a stopped one |
| `POST /api/bisects/{id}/deep {priority?}` | deep search of a bisection that ended without a commit |
| `DELETE /api/bisects/{id}` | delete a finished bisection: only the record goes, the steps stay runs of the branch and count in later searches |
| `POST /api/comparisons {base?, target, scenarios: [...], priority?}` | a comparison: an ABBA per scenario; for a PR without base, the branch against the branch + PR (`refs/pull/N/merge`), otherwise target against base or the merge base |
| `GET /api/comparisons?limit=`, `GET /api/comparisons/{id}` | comparisons (newest first) and one: status, verdict counters, ETA and a row per scenario; one also has `commits` and `bisects` |
| `POST /api/comparisons/{id}/cancel`, `DELETE /api/comparisons/{id}` | cancel / delete a finished one |
| `GET /api/jobs/{id}/profile?limit=` | the A/B diff of hot methods |
| `POST /api/notify/test`, `GET /api/notify/status` | test the notification channel |
| `POST /api/calibrate {ref?, scenario}` | an A/A calibration |
| `POST /api/resolve {ref}` | ref → sha and the subject of the commit |
| `GET /api/builds?limit=&sha=` | builds (with `sha`, the builds of one commit, also with the modules of scenarios); each has `logBytes` and `jarsBytes` when its log and jars can be downloaded |
| `GET /api/builds/{key}/log`, `GET /api/builds/{key}/jars` | the log of a build (text) and its Ignite jars as one zip; 404 with an explanation when the build cache evicted them |
| `POST /api/watch/poll|pause|resume` | poll now / auto-run off / on |
| `POST /api/worker/pause|resume` | "close for maintenance": finish the current job and take no new ones / take them again |
| `POST /api/kill-all` | kill every JVM of this stand with the marker `ignite.firewatch.run` |

Errors are JSON `{error, status}`: 400 (validation, unknown ref), 404, 409 (the mirror is not ready yet), 500.

## UI

Static files without a build step (`src/main/resources/web`): `app.js` (an ES module, hash routing), `charts.js` + vendored
uPlot 1.6.32 (`vendor/`). Menu: Dashboard · Timeline · Commits · Compare · Bisects · Queue | Noise · Scenarios · Settings
(the right group pushed to the right), and on the right a search box: a sha, `IGNITE-123`, `#PR`, a GitHub link, the number
of a job, bisection or comparison, words of a commit subject; `/` puts the cursor into it. The tab switches at once, so
while the page loads, a bar runs on top and the old page is dimmed.

A Δ is filled only where the metric decides the verdict of the job (ops/s, p99, CPU/op) or is the metric a bisection
follows; other shifts get a soft background. The metric columns are the same everywhere: ops/s, p99 µs, CPU µs/op,
alloc KB/op. Ticket and PR numbers in commit subjects lead to the tracker and to GitHub. What a table folds as calm stays
visible as a green line with the names. Dialogs are built the same way: a title, a body with one column of labels, the
summary and the buttons at the bottom; an irreversible action is red. Picking bisections is a table: scenario, metric, Δ,
strength of the signal, commits.

- **Dashboard** answers "is master all right". Until the first fetch is done, a Starting the stand card stands instead of
  all of it: the steps to the first measurement and the clone of the repository with the progress of git (`watcher.fetch`
  of `/api/health`: stage, percent, size and speed); the page refreshes every 2 s and turns into the usual one by itself;
  a failed fetch shows as the error of git at that step, with Try now. On top is the strip of the stand: Worker, Queue,
  Last commit, Noise (scenarios without A/A are neutral, "no A/A yet: default floors"), Auto-run; while bisections run,
  the Queue tile shows when they all end. Below is one matrix of master vs release: a row per scenario, those with
  something left to do on top. The colour of a Δ says how bad it is, the mark next to it what is left to do
  (`Attention`): To search (there is more to search), Searching (wait), No single commit (the search is over, there is no
  single commit, Deep search is left), Explained (the commits are named). A regression with a found commit stays red. The
  legend counts these groups, and a click on a group leads to its lines in Result. A click on a Δ with a mark opens the
  exact status, the commits, the Bisect or Deep search buttons and a link to Timeline; a Δ without a mark opens Timeline
  at once. Scenarios without changes and the fully explained ones fold into one line; a scenario with a verdict never
  folds. The heading has the number of commits since the release, the coverage breakdown in its tooltip. Below are the
  failed jobs not followed by a success and Result on master: the found commits with their effect by scenario, and Still
  open, what is still worth a look, cheap searches first, buttons on hover.
- Terms are explained on hover: status badges, metric headings, legends, ABBA, A/A, threshold, strength of the signal.
  There is one glossary (`HELP` in `app.js`), so the wording is the same on every page.
- One format for the whole UI: a date `30 Oct 15:32` (the year only when it is not the current one), a day `30 Oct`, a
  duration `45 s`, `8 min`, `1.5 h`. `no-change` is grey, only changes have colour; `start ms` and `preload ms` are not
  highlighted: they are the noise of a start.
- **Commits**: the first-parent history of the branch, 50 commits a page, by default since the last release, the whole of
  it on request, with an only measured filter: ops/s, p99, CPU µs/op, alloc KB/op, start, preload with the Δ to the
  parent next to the value: grey within the noise threshold, a soft background above the threshold without a verdict, a
  bright one with a regression/improvement verdict; Verdict; Runs, how many runs are pooled into the row. The Run button
  shows on hover over a row: ABBA against the parent, for a commit with a result one more run. A commit without a run of
  its own but inside a measured segment is marked `in N` with a link to Timeline.
- **Commit** (`#/commits/<sha>`): under the title a line of the builds of the commit (core and with the modules of
  scenarios): version, size, log and jars. "Runs vs parent": a "pooled" row over all the repeats and every run; below,
  "Other runs": calibrations, this commit as the baseline.
- **Compare**: manual debugging, with the modes "PR vs master" (the default) and "Ref vs ref": a link to a PR (or `pr/N`,
  any ref) and a set of scenarios; by default master against master + PR. Scenarios are picked with checkboxes by family;
  the chips All, Put/get, Transactions, SQL H2, SQL Calcite, 1 server and 2+ servers switch a whole group on and off; the
  groups come from the catalog, so a new scenario joins them by itself. Below is the list of comparisons, the running
  ones with their end time, bisections included. Jobs with the source `compare` stay off Dashboard, Commits, the repeats
  of the branch and the thresholds.
- **Comparison** (`#/comparisons/N`, the link can be shared): refs A and B, the status (`searching` when everything is
  measured and bisections run; the API keeps `done`, so the notification of the end does not wait for the searches), how
  many scenarios are measured, the verdict counters, what runs now and when everything ends, bisections included (the
  stand runs one job at a time, so the time of bisections adds to the jobs; on a pause there is no time), a row per
  scenario with the values of B and Δ, Run/Retry/Bisect buttons on a row, Cancel or Delete, Bisect deviations. The Result
  block: the found commits, each with the scenarios and metrics it moved and by how much, the biggest shift first, a Δ
  leading into its bisection; below, Not pinned down, shifts without a commit, in soft colours: smeared ranges
  (`spread`), shifts at the noise threshold (`inconclusive`, with Deep search), ranges still being searched, and
  deviations without a bisection (with Bisect). The Bisects block: a row per bisection with the course of the search, the
  found commits and their Δ, the share of the total Δ or the reason it stopped; the page refreshes while at least one runs.
- **Setup**: the wizard of a new stand in three steps: Application (repository, branch, release and the check of the
  builds, which runs by itself on opening and when the address or the branch changes), Hosts (where to run and Check) and
  Scenario sizes. The head of a step has its number (a tick once the check passed) and the state of its check; under each
  error is how to fix it. The menu is hidden and other pages are unavailable until the wizard is done. Start the stand is
  disabled until the check of the builds passed, and for remote hosts Check too; the reason is written next to the button.
- **Settings**: on top a summary of the stand: a verdict and a line each for the worker, watcher, disk, memory, JVMs left
  over, builds, noise, stand and automation (`✓`, `!`, `✗`), with Poll now, Kill all and, in the worker line, Close for
  maintenance / Reopen with a confirmation: the dialog shows the state of the stand at the moment of the click (the current
  job runs to its end, new ones do not start). Below are the sections Automation, Release, Notifications, Limits, Stand,
  System (builds with their log and jars, and the controller log), Application (the repository, the check of the builds
  as in the wizard, and the ticket keys: `links.tickets`, a table of key, ticket URL and PR URL, in the setting lines
  `KEY=URL [PR_URL]`, by default `IGNITE=https://issues.apache.org/jira/browse/`; the PR of a commit with that key leads to
  PR_URL + its number, an empty PR_URL comes from the Git URL). In Release, with or without a mask, only a tag of exactly
  `X.Y.Z` counts: `2.18.0-rc0` does not, unless written in full.
  Stand: the same fields and Check as in the wizard, plus Measure load and Clean hosts, until the first finished phase;
  after it the hosts and sizes are only shown, while the last Check report and Check again stay. Both checks say next to
  their badge when the report was made.
  Automation: what the stand runs by itself, three cards of one kind. On new commits: off, segments, every commit or a
  single phase, scenarios, a path filter. Segments, A/A and master vs release live by one rule, `Freshness`, and only its
  period is set: Keep fresh, every N days (1 for segments, 7 for the others). Younger than the period, nothing runs. Older,
  it runs when the stand has been idle for 2 hours, at a low priority. Twice the period old, it runs despite the queue,
  ahead of the next steps of bisections. A segment counts its age from its oldest unmeasured commit, A/A and master vs
  release per scenario. A/A short of the 3 its threshold needs are made up at once, and master vs release is skipped when
  there are no new commits. Each has its own set of scenarios, and a new scenario joins "all" by itself. While the stand is
  closed for maintenance nothing starts; a missed start goes after it reopens.
  Application: the git address and the main branch, under them the check of the builds (Builds), which runs when the
  section first opens and after the repository is saved. The values live in the DB; `IGNITE_FIREWATCH_REPO_URL` and
  `IGNITE_FIREWATCH_BRANCH` only set them on the first start. A stand without network gets a path on its machine instead of
  the address: a `git clone --mirror` directory or a `git bundle create … --all` file (under `/git` in Docker, `docker/git`
  or `IGNITE_FIREWATCH_GIT_DIR` on the host). Every poll runs `git fetch` from there, so a fresh copy put in place of the
  old one is picked up by itself; saving a path that does not read is refused with the error of git. A change is checked
  with `git ls-remote`, needs an empty queue and applies at once: the stand follows the new head, runs of the old branch
  stay in the DB but leave Commits and Timeline. Links to commits and PRs are built from this address. The auto-run modes
  are described in the section itself.
  Release: what the dashboard compares master with. Empty means the highest X.Y.Z tag, a mask `2.18.*` picks the highest
  one that fits, and any tag, sha or branch can be given. The value is checked when saved; runs against another release
  do not count on the dashboard.
  The sections switch without asking the server, and unsaved input is not lost. Limits, with an estimate of the space they
  hold next to each: how many comparisons and finished bisections to keep (0 = all, the default; when a limit goes down,
  the page asks for a confirmation), the heavy files of the last N jobs, the build cache, the auto-run queue. The history
  of the branch has no limit: Timeline, repeats and noise thresholds rest on it.
- **Noise**: a row per scenario: the Δ of the last A/A on ops/s, p99 and CPU/op in separate columns, how many calibrations
  (`2 of 3` while it has no thresholds of its own), the history unfolds. One button makes up A/A where there are fewer than
  three, the other runs all the scenarios.
- **Queue**: a compact block of the current job with Log and Cancel, the queue with priorities, the expected start and
  duration of every job, when what waits now ends and when the bisections end (they add steps one at a time), the active
  bisections, the history with a "without bisect steps" box; steps are marked "bisect #N · step k".
- **Bisects** (`#/bisects`): every bisection, the active ones on top, the chips All, Active, Needs a deep search, Found,
  No single commit (the choice shows in the address). A repeated search of the same range on the same metric is one row,
  the earlier numbers next to it; the same in Comparison.
- **Bisect** (`#/bisects/<id>`): the metric in the title and explained, the search map in the colours of Timeline: on top
  the whole segment, below ever narrower ranges, a stripe for what is still suspected, lines and outlines for the found
  commits (a click leads to the job of a block, dragging zooms in); the segment and its Δ, open ranges, the outcome or the
  reason it stopped, a table of findings (commits, smeared ranges, unconfirmed ones), a table of steps (what was measured,
  the job or reused, Δ, verdict, decision); in a deep search a row per pair with the Δ of every run, the quick search
  folded. Cancel/Resume/Deep search, Delete for a finished one, a link to Timeline.
- **Timeline**: on top an overview of every scenario on one scale of the commits since the release: a row per scenario
  shows where master is now. Hatching: the shortest run with a verdict, the shift somewhere inside it; a solid tick: a
  single commit. A pale fill after a shift: it is still in force. Only the direct master vs release measurement on the
  right decides the fill, so a shift that went away stays hatching without a fill, and when master moved but no run
  showed it, the hatching runs through the whole row; calm runs are blue; a click on a row opens the scenario, a click on
  a block also its run. From the menu only the overview opens: the details appear on a click on a row and close on a
  second click or with the button. A link to a given scenario (Dashboard, Commits, Bisect) does the opposite: the
  details are open, the overview folded. Below is the map of the scenario: rows by size, Δ on wide blocks. Scenario,
  metric, zoom and the chosen block are in the address (the link can be shared); blocks refined by a split are paler; an
  active bisection has a badge over the map and a dashed stripe through every row for each range it still searches.
- **Job**: among the actions a Download ▾ button: the zip of the job without JFR and with it, the log and the Ignite jars
  of the build of A and B; the menu asks for its content and sizes only when it opens. The error of a failed build leads
  straight into its log. In the table of phases "N files ▸" unfolds the files of a phase with their sizes under its row.
  The ABBA table (phases, A, B, Δ, CI, threshold, verdict): the verdict metrics and alloc/op, the others on a button; only
  the metrics that decide the verdict have one. A broken phase is marked. Overlaid per-second series: three charts, the
  others on a button, one legend for all with the values of the chart under the cursor. Files (logs, JFR). On a remote
  stand there is also a Layout block: which JVM is on which host, what that machine is, the CPU, GC and heap of each.
- **Scenarios**: the scenarios of the harness (Java classes): servers, client and threads, warmup/measurement, version and
  the sources of the class and its base classes from `harness.jar` (`harness.jar list`, read at the start of the
  controller).

Everything that queues jobs (Run again, Retry, split, Measure the commit, A/A, Compare, master vs release, Bisect and
deep search) shows a dialog first: what is queued, how long it takes, when it ends, and Priority: "as usual" (the default:
the priority of its kind, 1 for a manual run, 0 for an A/A) or "ahead of the queue" (right after the running job: one
above the highest priority waiting, at least 2, so also before the steps of bisections started ahead); the Done estimate
follows the choice.

A page is redrawn into a fresh container only when its content changed; a choice in a drop-down applies at once. Only
Queue and the pages of running work (a job, a bisection, comparisons) refresh by themselves; the others on navigation, a
change of their own filters or F5.

## Tests

`mvn -q test` runs the tests of the controller: statistics (verdicts, CI, noise), the SQLite layer, the path filter, the
git mirror on a temporary repository (resolve/log/merge-base/export/`pr/N`, a path or a bundle as the source, the
progress of a fetch), the check of the builds with a fake java and mvn, the downloads, and the end-to-end pipeline
through the service layer with `FAKE_BUILD` and a shell script instead of the harness: ABBA to `done` with a regression,
calibrations and the noise threshold, a failed phase, cancel during a phase, recovery after a restart, the watcher with
its filter/pause/backlog, compare via the merge base, comparisons (a job per scenario, status from the jobs, cancel,
delete, repeats), automatic bisection (the second half when the first one moved, two changes in different halves, reuse,
spread, confirm, cancel, stop/resume, a restart in the middle of a step). The UI smoke test
(`everyPageOfTheUiRendersAgainstTheApi`): a controller with the data of a segment, two bisections and two comparisons on a
local port, and `node src/test/resources/ui-smoke.mjs` renders every page of `app.js` against its API with a stub of the
browser and fails on the first one that throws; without `node` in PATH or the right to listen on a local port the test is
skipped. The git tests need `git` in PATH; the temporary repositories are created in `java.io.tmpdir`
(`-Dtest.tmpdir=…`).
