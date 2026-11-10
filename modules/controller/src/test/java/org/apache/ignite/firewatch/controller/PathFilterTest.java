package org.apache.ignite.firewatch.controller;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link PathFilter}. */
class PathFilterTest {
    /** */
    private final PathFilter def = PathFilter.parse(Settings.DEFAULT_PATH_FILTER);

    /** */
    @Test void defaultFilterMatchesCoreSources() {
        assertTrue(def.matches("modules/core/src/main/java/org/apache/ignite/internal/"
            + "processors/cache/GridCacheAdapter.java"));
        assertTrue(def.matches("modules/binary/impl/src/main/java/org/apache/ignite/internal/binary/BinaryUtils.java"));
        assertTrue(def.matches("modules/thin-client/api/src/main/java/X.java"));
        assertTrue(def.matches("pom.xml"));
        assertTrue(def.matches("modules/core/pom.xml"));
        assertTrue(def.matches("parent/pom.xml"));
    }

    /** */
    @Test void defaultFilterSkipsTestsDocsAndOtherModules() {
        assertFalse(def.matches("modules/core/src/test/java/org/apache/ignite/SomeTest.java"));
        assertFalse(def.matches("modules/indexing/src/main/java/X.java"));
        assertFalse(def.matches("README.md"));
        assertFalse(def.matches("docs/_docs/index.adoc"));
        assertFalse(def.matches(".github/workflows/ci.yml"));
    }

    /** */
    @Test void anyMatchAndEmptyFilter() {
        assertTrue(def.matchesAny(List.of("README.md", "modules/core/src/main/java/A.java")));
        assertFalse(def.matchesAny(List.of("README.md", "modules/core/src/test/java/A.java")));
        assertTrue(def.matchesAny(List.of()), "unknown file list passes");
        assertTrue(PathFilter.parse("").matches("anything"));
        assertTrue(PathFilter.parse(" , \n").isEmpty());
    }

    /** */
    @Test void badGlobIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PathFilter.parse("modules/[core"));
    }
}
