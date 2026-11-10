package org.apache.ignite.firewatch.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests of {@link Cli}. */
class CliTest {
    /** */
    @Test
    void parsesBothForms() {
        Cli c = Cli.parse(new String[] {"run", "--libs", "/l", "--phase=A1", "--flag", "--n", "3"}, 1);
        assertEquals("/l", c.req("libs"));
        assertEquals("A1", c.get("phase", "S"));
        assertEquals("true", c.get("flag", null));
        assertEquals(3, c.getInt("n", 0));
        assertEquals("S", c.get("missing", "S"));
        assertThrows(IllegalArgumentException.class, () -> c.req("missing"));
        assertThrows(IllegalArgumentException.class, () -> Cli.parse(new String[] {"oops"}, 0));
    }
}
