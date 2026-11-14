# Plugin example: Spring Data

A plugin of Firewatch: its scenarios run Ignite together with `spring-data-ext` of
[ignite-extensions](https://github.com/apache/ignite-extensions), and the stand builds that extension next to every
Ignite it measures. Its section in the UI compares the master of both with their last release.

## The scenario

`calcite-point` runs the statements of `sql-calcite-point` through a Spring Data repository: the same table of 100k rows,
70% SELECT of one row by primary key, 30% UPDATE of one row by primary key, a thin client in 16 threads, one server with
Calcite as the default engine. Each statement is a method of `PersonRepository` with `@Query` that names its columns, so it
is a fields query (`SqlFieldsQuery`) and goes to Calcite; a derived `findBy...` would be `SELECT *`, which is the older
`SqlQuery` that only H2 runs. Next to `sql-calcite-point` it shows what the repository costs.

## Build and turn on

```bash
mvn -q -pl examples/spring-data -am package
cp examples/spring-data/target/firewatch-spring-data.jar config/plugins/
```

Then in `config/ignite-firewatch.properties` (a restart applies it):

```properties
plugins = firewatch-spring-data.jar
# optional: another repository of the extension (a fork, a mirror) or another branch of its master pair
#plugin.spring-data.repo = https://github.com/apache/ignite-extensions.git
#plugin.spring-data.branch = master
```

The section Plugins appears. On the page of the plugin, Settings, set the release of the extension, for example
`ignite-spring-data-ext-3.1.0`: the Ignite of the release pair is the one its pom builds against (2.17.0 here), and the
release pair is compared with the master pair from then on.

The build also puts the jars of the release of the extension and its Spring into
`target/firewatch-spring-data-libs`: a stand with `FAKE_BUILD` takes them from `<jar name>-libs/` next to the jar.

## Writing a plugin

- `META-INF/firewatch-plugin.properties` names the plugin and its extension: `name`, `title`, `description`, `repo`,
  `branch`, `module` (a directory of the repository, as `mvn -pl` takes it) and, optionally, `package` (where the scenarios
  are).
- Everything the stand gives is `provided`: `ignite-firewatch-harness-api`, `ignite-core`, the extension and what it
  leaves `provided` itself (here Spring). The stand builds the extension of each pair and puts it with its dependencies
  on the classpath; a library both Ignite and the extension have stays the one of Ignite.
- A library of the scenarios themselves goes into the jar, shaded with a relocation: the jar is on the classpath before
  the build.
- The modules of Ignite the extension needs go to `@Bench(modules)` (here `indexing`, which `ignite-spring-data-ext`
  needs, and `calcite`).
- The sources of the scenarios go into the jar as resources, as `pom.xml` does here: the page of the plugin shows them.
