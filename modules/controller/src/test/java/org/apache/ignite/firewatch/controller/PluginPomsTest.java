package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link PluginPoms}: the version of Ignite an extension builds against. */
class PluginPomsTest {
    /** The chain of spring-data-ext as ignite-extensions has it: module, its parent, parent-internal, ignite-parent. */
    static Map<String, String> springData(String igniteVersion) {
        Map<String, String> m = new HashMap<>();
        m.put("modules/spring-data-ext/spring-data/pom.xml", "<project><parent><groupId>org.apache.ignite</groupId>"
            + "<artifactId>ignite-spring-data-parent-ext</artifactId><version>1</version></parent>"
            + "<artifactId>ignite-spring-data-ext</artifactId><version>3.2.0-SNAPSHOT</version></project>");
        m.put("modules/spring-data-ext/pom.xml", "<project><parent><groupId>org.apache.ignite</groupId>"
            + "<artifactId>ignite-parent-ext-internal</artifactId><version>1</version>"
            + "<relativePath>../../parent-internal/pom.xml</relativePath></parent>"
            + "<artifactId>ignite-spring-data-parent-ext</artifactId></project>");
        m.put("parent-internal/pom.xml", "<project>\n  <parent>\n    <groupId>org.apache.ignite</groupId>\n"
            + "    <artifactId>ignite-parent</artifactId>\n    <version>" + igniteVersion + "</version>\n"
            + "    <relativePath /> <!-- lookup parent from repository -->\n  </parent>\n"
            + "  <artifactId>ignite-parent-ext-internal</artifactId>\n  <version>1</version>\n</project>\n");

        return m;
    }

    /** */
    @Test void theChainOfParentsEndsInTheVersionOfIgnite() throws IOException {
        Map<String, String> repo = springData("2.17.0");
        PluginPoms.Found f = PluginPoms.igniteParent(repo::get, "modules/spring-data-ext/spring-data");
        assertEquals("parent-internal/pom.xml", f.pom());
        assertEquals("2.17.0", f.version());

        assertEquals("2.20.0-SNAPSHOT", PluginPoms.igniteParent(springData("2.20.0-SNAPSHOT")::get,
            "modules/spring-data-ext/spring-data").version(), "master builds against the snapshot of Ignite");
    }

    /** */
    @Test void aChainThatLeavesTheRepositoryOrNeverNamesIgniteIsAnError() {
        Map<String, String> repo = springData("2.17.0");
        repo.remove("parent-internal/pom.xml");
        assertTrue(assertThrows(IOException.class,
            () -> PluginPoms.igniteParent(repo::get, "modules/spring-data-ext/spring-data")).getMessage()
            .contains("parent-internal/pom.xml"));

        Map<String, String> other = Map.of("m/pom.xml", "<project><parent><groupId>org.example</groupId>"
            + "<artifactId>p</artifactId><version>1</version><relativePath/></parent></project>");
        assertTrue(assertThrows(IOException.class, () -> PluginPoms.igniteParent(other::get, "m")).getMessage()
            .contains("never name"));
        assertThrows(IOException.class, () -> PluginPoms.igniteParent(repo::get, "no/such"));
    }

    /** */
    @Test void relativePathsAreResolvedFromThePom() {
        assertEquals("modules/a/pom.xml", PluginPoms.resolve("modules/a/b/pom.xml", "../pom.xml"));
        assertEquals("parent-internal/pom.xml", PluginPoms.resolve("modules/a/pom.xml", "../../parent-internal"));
        assertEquals("pom.xml", PluginPoms.resolve("m/pom.xml", ".."));
        assertEquals("x/y.xml", PluginPoms.resolve("x/pom.xml", "./y.xml"));
    }

    /** Only the version of the parent changes: the extension builds against the Ignite of the build. */
    @Test void theVersionOfTheParentIsReplacedAndNothingElse() {
        String pom = springData("2.20.0-SNAPSHOT").get("parent-internal/pom.xml");
        String got = PluginPoms.withParentVersion(pom, "2.20.0-SNAPSHOT-local");
        assertTrue(got.contains("<version>2.20.0-SNAPSHOT-local</version>\n    <relativePath />"));
        assertTrue(got.contains("<artifactId>ignite-parent-ext-internal</artifactId>\n  <version>1</version>"),
            "the version of the pom itself stays");
        assertThrows(IllegalArgumentException.class, () -> PluginPoms.withParentVersion("<project/>", "1"));
    }
}
