package org.apache.ignite.firewatch.harness;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link Catalog}. */
class CatalogTest {
    /** */
    @Test void everyScenarioIsFoundWithItsTopology() {
        Map<String, Catalog.Entry> all = Catalog.all().stream().collect(Collectors.toMap(Catalog.Entry::id, e -> e));
        assertEquals(List.of("putget-thick-t4", "putget-thin-t16", "putget-thin-t16-2srv-fullsync",
            "putget-thin-t16-pds", "putget-thin-t16-ssl", "sql-calcite-point", "sql-calcite-point-2srv",
            "sql-calcite-query", "sql-calcite-query-2srv", "sql-h2-point", "sql-h2-point-2srv", "sql-h2-query",
            "sql-h2-query-2srv", "tx-thick-t16-2srv", "tx-thin-t16-2srv", "tx-thin-t16-3srv"),
            all.keySet().stream().sorted().toList());
        Catalog.Entry main = all.get("putget-thin-t16");
        assertEquals(16, main.bench.loader().threads());
        assertEquals("THIN", main.bench.loader().client().name());
        assertEquals(1, main.bench.servers().count());
        assertEquals(120, main.totalSeconds());
        assertEquals("THICK", all.get("putget-thick-t4").bench.loader().client().name());
        assertEquals("putget-thick-t4", all.get("putget-thick-t4").describe(false).get("title"), "no title: the id");
        assertEquals(List.of(), main.describe(false).get("modules"));
        assertEquals(List.of("indexing", "calcite"), all.get("sql-h2-point").describe(false).get("modules"));
        assertEquals(3, all.get("tx-thin-t16-3srv").bench.servers().count());
        assertEquals(2, all.get("sql-calcite-query-2srv").bench.servers().count());
        assertEquals("THICK", all.get("tx-thick-t16-2srv").bench.loader().client().name());
    }

    /** */
    @Test void hashIsIdAndVersionOnly() {
        Catalog.Entry e = Catalog.get("putget-thin-t16");
        assertEquals(Json.sha256("putget-thin-t16:1"), e.hash());
        assertNotEquals(e.hash(), Catalog.get("putget-thick-t4").hash());
        assertThrows(IllegalArgumentException.class, () -> Catalog.get("nope"));
    }

    /** */
    @Test void sourcesOfTheClassAndItsBasesAreListed() {
        List<Map<String, String>> src = Catalog.get("putget-thin-t16-ssl").sources();
        assertEquals(List.of("PutGetSsl.java", "PutGetInMemory.java", "PutGet.java"),
            src.stream().map(m -> m.get("file")).toList());
        assertTrue(src.get(0).get("text").contains("setSslEnabled(true)"),
            "real source text, from the classes directory in tests");
    }

    /** The launcher and the controller run {@code list} with only the harness jar: no Ignite classes may be needed. */
    @Test void catalogWorksWithoutIgniteOnTheClasspath() throws Exception {
        URL classes = Catalog.class.getProtectionDomain().getCodeSource().getLocation();
        URL[] cp = {
            classes,
            com.fasterxml.jackson.databind.ObjectMapper.class.getProtectionDomain().getCodeSource().getLocation(),
            com.fasterxml.jackson.core.JsonFactory.class.getProtectionDomain().getCodeSource().getLocation(),
            com.fasterxml.jackson.annotation.JsonInclude.class.getProtectionDomain().getCodeSource().getLocation(),
        };

        try (URLClassLoader cl = new URLClassLoader(cp, ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> cl.loadClass("org.apache.ignite.Ignition"));
            Class<?> cat = cl.loadClass(Catalog.class.getName());
            List<?> all = (List<?>)cat.getMethod("all").invoke(null);
            assertEquals(16, all.size());
            Object entry = all.get(0);
            Map<?, ?> d = (Map<?, ?>)entry.getClass().getMethod("describe", boolean.class).invoke(entry, true);
            assertEquals("putget-thick-t4", d.get("name"));
            assertTrue(((List<?>)d.get("sources")).size() >= 2);
        }

        assertTrue(Path.of(classes.toURI()).toFile().isDirectory());
    }
}
