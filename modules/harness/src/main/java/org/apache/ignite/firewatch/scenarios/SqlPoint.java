package org.apache.ignite.firewatch.scenarios;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.ignite.firewatch.harness.api.Scale;
import org.apache.ignite.firewatch.harness.api.Session;

/**
 * Point statements: 70% SELECT of one row by primary key, 30% UPDATE of one row by primary key, on 100k rows with a
 * 100-character value. The work per row is tiny, so the cost of the engine per statement (parsing, plan cache,
 * mapping, execution) is what is measured.
 */
public abstract class SqlPoint extends SqlScenario {
    /** */
    static final int ROWS = 100_000;

    /** */
    static final double SELECT_RATIO = 0.7;

    /** A row with its primary key and overhead in the data region. */
    private static final int ROW_BYTES = 400;

    /** */
    private String val;

    /** */
    private int rows = ROWS;

    /** {@inheritDoc} */
    @Override public void setup(Session s) {
        rows = Scale.current().entries(ROWS, ROW_BYTES, 1, true);
        Random r = new Random(42);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < 100; i++)
            sb.append((char)('a' + r.nextInt(26)));

        val = sb.toString();
        sql(s, "CREATE TABLE KV (ID INT PRIMARY KEY, VAL VARCHAR)");
        insert(s, "KV", "ID, VAL", rows, i -> new Object[] {i, val});
        checkEngine(s, "SELECT VAL FROM KV WHERE ID = 1");
    }

    /** {@inheritDoc} */
    @Override public void op(Session s, ThreadLocalRandom rnd) {
        int id = rnd.nextInt(rows);

        if (rnd.nextDouble() < SELECT_RATIO)
            sql(s, "SELECT VAL FROM KV WHERE ID = ?", id);
        else
            sql(s, "UPDATE KV SET VAL = ? WHERE ID = ?", val, id);
    }
}
