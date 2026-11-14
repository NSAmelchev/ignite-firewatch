package org.apache.ignite.firewatch.examples.springdata;

/**
 * The domain type of {@link PersonRepository}: the repository is typed by it, while its statements read and write the
 * columns of table {@code PERSON} directly.
 */
public class Person {
    /** */
    private int id;

    /** */
    private String name;

    /**
     * Returns the primary key.
     *
     * @return Id.
     */
    public int getId() {
        return id;
    }

    /**
     * Returns the name.
     *
     * @return Name.
     */
    public String getName() {
        return name;
    }
}
