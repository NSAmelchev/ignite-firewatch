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

Without Docker: build the distribution with JDK 17 and Maven, run it anywhere with JDK 17 and git (it brings Maven):

```bash
mvn -DskipTests package     # target/ignite-firewatch-<version>-bin.zip: unpack it anywhere
bin/firewatch.sh start      # in the unpacked directory; also stop, restart, status, logs
```

Without internet access the stand still needs the dependencies of Ignite (a mirror in `~/.m2/settings.xml`) and a git
repository it can fetch (`repo.url`).

Open http://localhost:8090 and follow the setup wizard. Settings (port, directories, memory, timeouts) are in
[config/ignite-firewatch.properties](config/ignite-firewatch.properties). To deploy to another host over ssh:
`IGNITE_FIREWATCH_HOST=user@host docker/deploy.sh`.

## More

- [DEVELOPMENT.md](DEVELOPMENT.md): build, tests, adding a scenario.
- [docs/DESIGN.md](docs/DESIGN.md): how the stand works and why.
- Module details, in Russian: [controller](modules/controller/README.md), [harness](modules/harness/README.md).

## License

[Apache License 2.0](LICENSE); the bundled uPlot is [MIT](modules/controller/src/main/resources/web/vendor/uPlot.LICENSE).
Firewatch is not an Apache Software Foundation project; Apache Ignite is a trademark of the ASF.
