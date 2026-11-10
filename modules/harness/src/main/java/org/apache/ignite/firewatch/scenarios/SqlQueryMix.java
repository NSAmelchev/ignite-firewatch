package org.apache.ignite.firewatch.scenarios;

import java.util.concurrent.ThreadLocalRandom;

import org.apache.ignite.firewatch.harness.api.Scale;
import org.apache.ignite.firewatch.harness.api.Session;

/**
 * Queries that read many rows, a third each: a range on a secondary index (100 rows), GROUP BY with aggregates over
 * an index range (1000 rows into 50 groups) and a join of the persons of one organization with it (100 rows,
 * colocated by the affinity key). 100k persons in 1000 organizations; one operation is one query, all rows read.
 */
public abstract class SqlQueryMix extends SqlScenario {
    /** */
    static final int PERSONS = 100_000;

    /** */
    static final int ORGS = 1_000;

    /** A person with its keys, two indexes and overhead in the data region. */
    private static final int PERSON_BYTES = 500;

    /** */
    private static final int SALARY_STEP = 7919;

    /** */
    private int persons = PERSONS;

    /** {@inheritDoc} */
    @Override public void setup(Session s) {
        persons = Scale.current().entries(PERSONS, PERSON_BYTES, 1, true);

        if (persons % SALARY_STEP == 0)
            persons++; // the step has to stay prime to the number of persons

        sql(s, "CREATE TABLE ORG (ID INT PRIMARY KEY, NAME VARCHAR)");
        sql(s, "CREATE TABLE PERSON (ID INT, ORG_ID INT, AGE INT, SALARY INT, NAME VARCHAR, PRIMARY KEY (ID, ORG_ID)) "
            + "WITH \"AFFINITY_KEY=ORG_ID\"");
        sql(s, "CREATE INDEX PERSON_SALARY ON PERSON (SALARY)");
        sql(s, "CREATE INDEX PERSON_ORG ON PERSON (ORG_ID)");
        insert(s, "ORG", "ID, NAME", ORGS, i -> new Object[] {i, "org-" + i});
        insert(s, "PERSON", "ID, ORG_ID, AGE, SALARY, NAME", persons,
            i -> new Object[] {i, i % ORGS, 20 + i % 50, salary(i), "person-" + i});
        checkEngine(s, "SELECT ID FROM PERSON WHERE SALARY BETWEEN 0 AND 99");
    }

    /** A permutation of 0..persons-1: a salary range of width w holds exactly w persons. */
    private int salary(int id) {
        return (int)((id * (long)SALARY_STEP) % persons);
    }

    /** {@inheritDoc} */
    @Override public void op(Session s, ThreadLocalRandom rnd) {
        switch (rnd.nextInt(3)) {
            case 0: {
                int lo = rnd.nextInt(persons - 100);
                sql(s, "SELECT ID, NAME, SALARY FROM PERSON WHERE SALARY BETWEEN ? AND ?", lo, lo + 99);
                break;
            }
            case 1: {
                int lo = rnd.nextInt(persons - 1000);
                sql(s, "SELECT AGE, COUNT(*), AVG(SALARY) FROM PERSON WHERE SALARY BETWEEN ? AND ? GROUP BY AGE", lo,
                    lo + 999);
                break;
            }
            default:
                sql(s, "SELECT P.NAME, O.NAME FROM PERSON P JOIN ORG O ON P.ORG_ID = O.ID WHERE P.ORG_ID = ?",
                    rnd.nextInt(ORGS));
        }
    }
}
