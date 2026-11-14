# Firewatch

Performance tracking for [Apache Ignite](https://ignite.apache.org) master. Firewatch builds each new commit, runs
load tests against its parent in ABBA order and shows on a dashboard what got slower or faster and which commit did it.

- Verdicts beat a noise floor measured by A/A runs; a moved range is bisected down to the commit.
- Compare any two refs: a pull request against master, master against a release.
- Metrics: throughput, p99, server CPU and heap allocation per operation.
- 16 scenarios (put/get, persistence, TLS, transactions, SQL on H2 and Calcite); a scenario is one Java class.
- One container on a 4 vCPU / 5 GiB machine.

## Run

With Docker:

```bash
docker volume create ignite-firewatch-data && docker volume create ignite-firewatch-cache
docker compose -f docker/docker-compose.yml up -d --build
```

Without Docker, on a machine with JDK 17, Maven and git:

```bash
mvn -DskipTests package     # target/ignite-firewatch-<version>-bin.zip: unpack it anywhere
bin/firewatch.sh start      # in the unpacked directory; also stop, restart, status, logs
```

Without internet access the stand still needs the dependencies of Ignite (a mirror in a settings.xml named by
`maven.settings` of the config, or a local repository filled where the network is, which Maven 3.9 or newer only reads
next to the one of the stand: `maven.args = -o -Dmaven.repo.local.tail=/absolute/DIR`) and the sources: `repo.url` (the setup wizard, Settings → Application) takes a path on
the machine of the stand as well as a URL. Copy there a repository made where the network is, either as a directory or
as one file:

```bash
git clone --mirror https://github.com/apache/ignite.git ignite.git   # later: git -C ignite.git fetch --prune
git -C ignite.git bundle create ignite.bundle --all                   # the same as one file
```

Every poll of the stand fetches from that path, so a fresh copy put in place of the old one arrives by itself: rsync of
the directory carries only the new packs; a new bundle goes in with `mv` (a half-copied file fails one poll). A mirror
clone of GitHub keeps the pull requests too, so `pr/N` works. In Docker the directory `docker/git`
(`IGNITE_FIREWATCH_GIT_DIR`) is `/git` in the container: `repo.url = /git/ignite.git`.

Open http://localhost:8090 and follow the setup wizard. Settings (port, directories, memory, timeouts) are in
[config/ignite-firewatch.properties](config/ignite-firewatch.properties). To deploy to another host over ssh:
`IGNITE_FIREWATCH_HOST=user@host docker/deploy.sh`.

## More

- [DEVELOPMENT.md](DEVELOPMENT.md): build, tests, adding a scenario.
- [docs/DESIGN.md](docs/DESIGN.md): how the stand works and why.
- Module details: [controller](modules/controller/README.md), [harness](modules/harness/README.md).
- Plugins, scenarios that run Ignite together with an extension of another repository: the
  [example of Spring Data](examples/spring-data/README.md).

## License

[Apache License 2.0](LICENSE); the bundled uPlot is [MIT](modules/controller/src/main/resources/web/vendor/uPlot.LICENSE).
Firewatch is not an Apache Software Foundation project; Apache Ignite is a trademark of the ASF.
