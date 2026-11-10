package org.apache.ignite.firewatch.scenarios;

import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;

import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.QueryEngineConfiguration;
import org.apache.ignite.configuration.SqlConfiguration;
import org.apache.ignite.firewatch.harness.api.Env;
import org.apache.ignite.firewatch.harness.api.Scale;
import org.apache.ignite.firewatch.harness.api.Scenario;
import org.apache.ignite.firewatch.harness.api.Session;

/**
 * SQL over the thin client on one in-memory server. The node gets exactly one query engine, set explicitly, and both
 * engine modules are on the classpath of every SQL scenario, so an H2 scenario and its Calcite twin differ only in
 * {@link #engine()}: the same tables, data and statements.
 */
public abstract class SqlScenario extends Scenario {
    /**
     * Rows of one INSERT that fills a table. Calcite before 2.18 plans a multi-row VALUES exponentially (IGNITE-27738):
     * 20 rows already take the whole heap of a node, and every compared version has to load the same data.
     */
    private static final int INSERT_BATCH = 10;

    /** The SQL engines of Ignite 2.x by their configuration classes (modules ignite-indexing and ignite-calcite). */
    protected enum Engine {
        /** */
        H2("org.apache.ignite.indexing.IndexingQueryEngineConfiguration"),
        /** */
        CALCITE("org.apache.ignite.calcite.CalciteQueryEngineConfiguration");

        /** */
        private final String cfgClass;

        /** */
        Engine(String cfgClass) {
            this.cfgClass = cfgClass;
        }
    }

    /** */
    protected abstract Engine engine();

    /** {@inheritDoc} */
    @Override public void configureServer(IgniteConfiguration cfg, Env env) throws Exception {
        // the harness compiles against ignite-core only: the engine configuration is created from its module by name
        QueryEngineConfiguration qe =
            (QueryEngineConfiguration)Class.forName(engine().cfgClass).getConstructor().newInstance();
        cfg.setSqlConfiguration(new SqlConfiguration().setQueryEnginesConfiguration(qe.setDefault(true)));
        long region = Scale.current().regionMb(256, true);
        cfg.setDataStorageConfiguration(new DataStorageConfiguration()
            .setDefaultDataRegionConfiguration(new DataRegionConfiguration()
                .setInitialSize(region << 20)
                .setMaxSize(region << 20)));
    }

    /**
     * Fails unless {@code select} is planned by {@link #engine()}, so a node that ignores the configuration never
     * passes one engine's numbers off as the other's: Calcite plans are trees of Ignite* nodes, H2 prints SQL.
     */
    protected void checkEngine(Session s, String select) {
        String plan = String.valueOf(sql(s, "EXPLAIN PLAN FOR " + select).get(0).get(0));
        boolean calcite = plan.contains("Ignite");

        if (calcite != (engine() == Engine.CALCITE))
            throw new IllegalStateException("expected the " + engine() + " engine, the plan says otherwise: " + plan);
    }

    /** Runs a statement and reads every row of the result. */
    protected static List<List<?>> sql(Session s, String sql, Object... args) {
        return s.client().query(new SqlFieldsQuery(sql).setArgs(args)).getAll();
    }

    /** Fills a table by multi-row INSERTs; {@code row.apply(i)} gives the column values of row i. */
    protected static void insert(Session s, String table, String columns, int rows, IntFunction<Object[]> row) {
        int cols = columns.split(",").length;
        String tuple = "(" + String.join(", ", Collections.nCopies(cols, "?")) + ")";

        for (int from = 0; from < rows; from += INSERT_BATCH) {
            int n = Math.min(INSERT_BATCH, rows - from);
            Object[] args = new Object[n * cols];

            for (int i = 0; i < n; i++)
                System.arraycopy(row.apply(from + i), 0, args, i * cols, cols);

            sql(s, "INSERT INTO " + table + " (" + columns + ") VALUES "
                + String.join(", ", Collections.nCopies(n, tuple)), args);
        }
    }
}
