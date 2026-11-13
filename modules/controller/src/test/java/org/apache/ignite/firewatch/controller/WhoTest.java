package org.apache.ignite.firewatch.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tests of {@link Who}. */
class WhoTest {
    /** */
    @Test void aNameIsTwoToFortyLettersDigitsSpacesAndAFewSigns() {
        assertEquals("jdoe", Who.normalize("jdoe"));
        assertEquals("Иван Петров", Who.normalize("  Иван   Петров "), "ends cut, one space between words");
        assertEquals("i.petrov@example.org", Who.normalize("i.petrov@example.org"));
        assertEquals("a-b_c", Who.normalize("a-b_c"));
        assertEquals("x".repeat(40), Who.normalize("x".repeat(40)));
        assertNull(Who.normalize("x"), "too short");
        assertNull(Who.normalize("x".repeat(41)), "too long");
        assertNull(Who.normalize(".dot"), "a letter or a digit first");
        assertNull(Who.normalize("a<b>"));
        assertEquals("tab here", Who.normalize("tab\there"), "a tab becomes a space");
        assertEquals("José", Who.normalize("José"), "an accent typed apart is one letter, as the UI sends it");
        assertNull(Who.normalize(""));
        assertNull(Who.normalize(null));
    }

    /** The UI sends the name URL-encoded: a header carries no Cyrillic. */
    @Test void theHeaderIsDecoded() {
        assertEquals("Иван", Who.fromHeader(URLEncoder.encode("Иван", StandardCharsets.UTF_8)));
        assertEquals("a b", Who.fromHeader("a%20b"));
        assertNull(Who.fromHeader("%zz"), "not URL encoding");
        assertNull(Who.fromHeader("%3Cscript%3E"));
        assertNull(Who.fromHeader(null));
    }
}
