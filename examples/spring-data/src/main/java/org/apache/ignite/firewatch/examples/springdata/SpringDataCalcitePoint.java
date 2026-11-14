package org.apache.ignite.firewatch.examples.springdata;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.QueryEngineConfiguration;
import org.apache.ignite.configuration.SqlConfiguration;
import org.apache.ignite.firewatch.harness.api.Bench;
import org.apache.ignite.firewatch.harness.api.Client;
import org.apache.ignite.firewatch.harness.api.Env;
import org.apache.ignite.firewatch.harness.api.Loader;
import org.apache.ignite.firewatch.harness.api.Scale;
import org.apache.ignite.firewatch.harness.api.Scenario;
import org.apache.ignite.firewatch.harness.api.Session;
import org.apache.ignite.springdata.repository.config.EnableIgniteRepositories;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * {@code sql-calcite-point} through a Spring Data repository: the same table, rows and statements (70% SELECT of one
 * row by primary key, 30% UPDATE of one row by primary key), each one a method of {@link PersonRepository} over the
 * thin client. Next to {@code sql-calcite-point} it shows what the repository costs; against the release pair it shows
 * how master of Ignite and master of the extension together do.
 */
@Bench(id = "calcite-point", version = 1,
    description = "Spring Data on Calcite: a repository over the thin client, 16 threads, 1 server, 100k rows, "
        + "70% SELECT by primary key, 30% UPDATE by primary key",
    modules = {"indexing", "calcite"},
    loader = @Loader(client = Client.THIN, threads = 16, heapMb = 256),
    setupTimeoutSec = 300)
public class SpringDataCalcitePoint extends Scenario {
    /** The cache of table {@code PERSON}, which the repository names. */
    static final String CACHE = "Person";

    /** */
    private static final int ROWS = 100_000;

    /** */
    private static final double SELECT_RATIO = 0.7;

    /** A row with its primary key and overhead in the data region. */
    private static final int ROW_BYTES = 400;

    /** Rows of one INSERT that fills the table: Calcite before 2.18 plans a long multi-row VALUES exponentially. */
    private static final int INSERT_BATCH = 10;

    /** The repository of this JVM: one Spring context for all the threads of the loader. */
    private static volatile PersonRepository repo;

    /** */
    private String val;

    /** */
    private int rows = ROWS;

    /** {@inheritDoc} */
    @Override public void configureServer(IgniteConfiguration cfg, Env env) throws Exception {
        // the plugin compiles against ignite-core only: the engine configuration is created from its module by name
        QueryEngineConfiguration qe = (QueryEngineConfiguration)Class
            .forName("org.apache.ignite.calcite.CalciteQueryEngineConfiguration").getConstructor().newInstance();
        cfg.setSqlConfiguration(new SqlConfiguration().setQueryEnginesConfiguration(qe.setDefault(true)));
        long region = Scale.current().regionMb(256, true);
        cfg.setDataStorageConfiguration(new DataStorageConfiguration()
            .setDefaultDataRegionConfiguration(new DataRegionConfiguration()
                .setInitialSize(region << 20)
                .setMaxSize(region << 20)));
    }

    /** {@inheritDoc} */
    @Override public void setup(Session s) {
        rows = Scale.current().entries(ROWS, ROW_BYTES, 1, true);
        Random r = new Random(42);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < 100; i++)
            sb.append((char)('a' + r.nextInt(26)));

        val = sb.toString();
        sql(s, "CREATE TABLE PERSON (ID INT PRIMARY KEY, NAME VARCHAR) WITH \"CACHE_NAME=" + CACHE + "\"");
        String tuple = "(?, ?)";

        for (int from = 0; from < rows; from += INSERT_BATCH) {
            int n = Math.min(INSERT_BATCH, rows - from);
            Object[] args = new Object[n * 2];

            for (int i = 0; i < n; i++) {
                args[i * 2] = from + i;
                args[i * 2 + 1] = val;
            }

            sql(s, "INSERT INTO PERSON (ID, NAME) VALUES " + String.join(", ", Collections.nCopies(n, tuple)), args);
        }

        // Calcite plans are trees of Ignite* nodes, H2 prints SQL: a node that ignored the configuration fails here
        String plan = String.valueOf(sql(s, "EXPLAIN PLAN FOR SELECT NAME FROM PERSON WHERE ID = 1").get(0).get(0));

        if (!plan.contains("Ignite"))
            throw new IllegalStateException("expected the Calcite engine, the plan says otherwise: " + plan);

        if (repo(s).nameById(1).isEmpty())
            throw new IllegalStateException("the repository does not see the rows of the table");
    }

    /** {@inheritDoc} */
    @Override public void op(Session s, ThreadLocalRandom rnd) {
        int id = rnd.nextInt(rows);

        if (rnd.nextDouble() < SELECT_RATIO)
            repo(s).nameById(id);
        else
            repo(s).rename(val, id);
    }

    /** The repository, made on first use: Spring finds it by {@link EnableIgniteRepositories} over the thin client. */
    private static PersonRepository repo(Session s) {
        PersonRepository r = repo;

        if (r != null)
            return r;

        synchronized (SpringDataCalcitePoint.class) {
            if (repo == null) {
                AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
                // the client belongs to the loader: Spring must not close it with the context
                ctx.registerBean("igniteInstance", IgniteClient.class, s::client, bd -> bd.setDestroyMethodName(""));
                ctx.register(Repositories.class);
                ctx.refresh();
                repo = ctx.getBean(PersonRepository.class);
            }

            return repo;
        }
    }

    /** Runs a statement and reads every row of the result. */
    private static List<List<?>> sql(Session s, String sql, Object... args) {
        return s.client().query(new SqlFieldsQuery(sql).setArgs(args)).getAll();
    }

    /** The Spring configuration of the repositories. */
    @Configuration(proxyBeanMethods = false)
    @EnableIgniteRepositories(basePackageClasses = PersonRepository.class)
    static class Repositories {
    }
}
