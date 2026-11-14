# Firewatch: controller + harness + Maven/git toolchain for building Apache Ignite commits. The build context is the
# repository root (see docker-compose.yml). Stage 1 builds our jars; stage 2 is the runtime (JDK 17 + Maven, plus git).

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# Dependency layer: re-downloaded only when a pom changes (no BuildKit cache mounts: the legacy builder must work too).
COPY pom.xml .
COPY modules/harness-api/pom.xml modules/harness-api/
COPY modules/harness/pom.xml modules/harness/
COPY modules/controller/pom.xml modules/controller/
COPY modules/assembly/pom.xml modules/assembly/
COPY examples/spring-data/pom.xml examples/spring-data/
RUN mvn -B -q -ntp -DskipTests -pl modules/harness-api,modules/harness,modules/controller dependency:go-offline || true
COPY modules modules
COPY examples examples
# Only the two jars: the image is the distribution of its own. The layout check belongs to the build of the sources
# (CI): a deploy never fails on it.
RUN mvn -B -q -ntp -DskipTests -Dcheckstyle.skip -pl modules/harness-api,modules/harness,modules/controller package

FROM maven:3.9-eclipse-temurin-17
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl ca-certificates procps openssh-client rsync \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/modules/controller/target/controller.jar /src/modules/harness/target/harness.jar ./
# The paths and the port inside the container; everything else comes from /app/config/ignite-firewatch.properties.
# The controller finds harness.jar next to its own jar.
ENV IGNITE_FIREWATCH_DATA_DIR=/data \
    IGNITE_FIREWATCH_CACHE_DIR=/cache \
    IGNITE_FIREWATCH_PORT=8080
VOLUME ["/data", "/cache"]
EXPOSE 8080
# Small, single-threaded-GC JVM for the controller: the memory belongs to Maven and the benchmark JVMs.
ENTRYPOINT ["java", "-Xmx128m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Djava.io.tmpdir=/tmp", "-jar", "/app/controller.jar"]
