package org.apache.ignite.firewatch.examples.springdata;

import java.util.List;

import org.apache.ignite.springdata.repository.IgniteRepository;
import org.apache.ignite.springdata.repository.config.Query;
import org.apache.ignite.springdata.repository.config.RepositoryConfig;

/**
 * The statements of {@code sql-calcite-point} as a Spring Data repository. A statement that names its columns is a
 * fields query ({@code SqlFieldsQuery}), so it goes to the default engine of the node, Calcite here; a derived
 * {@code findBy...} would be {@code SELECT *}, the older {@code SqlQuery} that only H2 runs.
 */
@RepositoryConfig(cacheName = SpringDataCalcitePoint.CACHE)
public interface PersonRepository extends IgniteRepository<Person, Integer> {
    /**
     * Reads the name of a row by its primary key.
     *
     * @param id Primary key.
     * @return The rows of the result: one row with the name.
     */
    @Query("SELECT NAME FROM PERSON WHERE ID = ?")
    List<List<?>> nameById(int id);

    /**
     * Sets the name of a row by its primary key.
     *
     * @param name New name.
     * @param id Primary key.
     * @return The rows of the result: one row with the number of updated rows.
     */
    @Query("UPDATE PERSON SET NAME = ? WHERE ID = ?")
    List<List<?>> rename(String name, int id);
}
