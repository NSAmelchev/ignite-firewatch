package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Shared Jackson mapper (lenient on unknown fields: result files may grow with the harness). */
public final class Json {
    /** */
    public static final ObjectMapper MAPPER = JsonMapper.builder()
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    /**
     * Serializes an object to JSON.
     *
     * @param o Object.
     * @return JSON text.
     */
    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Parses JSON text into a tree.
     *
     * @param json JSON text, may be {@code null}.
     * @return JSON tree, or {@code null} if the text is {@code null}.
     */
    public static JsonNode tree(String json) {
        try {
            return json == null ? null : MAPPER.readTree(json);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Bad JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Parses a JSON file into a tree.
     *
     * @param file JSON file.
     * @return JSON tree.
     * @throws IOException If the file cannot be read.
     */
    public static JsonNode readTree(Path file) throws IOException {
        return MAPPER.readTree(Files.readString(file));
    }

    /**
     * Parses JSON into an object.
     *
     * @param json JSON text.
     * @param type Result class.
     * @param <T> Result type.
     * @return Parsed object.
     */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Bad JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** */
    private Json() {
    }
}
