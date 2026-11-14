package org.apache.ignite.firewatch.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link OpenApi}: the schemas of the entities and the document of the routes. */
class OpenApiTest {
    /** */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> props(Map<String, Object> schemas, String name) {
        return (Map<String, Object>)((Map<String, Object>)schemas.get(name)).get("properties");
    }

    /** A job as Jackson writes it: its fields, its is-getters, its phases by reference. */
    @Test void theSchemaOfAnEntityIsWhatJacksonWrites() {
        Map<String, Object> schemas = new LinkedHashMap<>();
        assertEquals(Map.of("$ref", "#/components/schemas/Job"),
            OpenApi.schemaOf(Json.MAPPER.constructType(Job.class), schemas));

        Map<String, Object> job = props(schemas, "Job");
        assertEquals("integer", ((Map<?, ?>)job.get("id")).get("type"));
        assertEquals("string", ((Map<?, ?>)job.get("plugin")).get("type"));
        assertTrue(((String)((Map<?, ?>)job.get("plugin")).get("description")).contains("plugin"),
            "the fields of ApiDocs describe it");
        assertFalse(((Map<?, ?>)job.get("comparison")).containsKey("type"), "a JsonNode is any value");
        assertEquals("array", ((Map<?, ?>)job.get("runs")).get("type"));
        assertEquals(Map.of("$ref", "#/components/schemas/Run"), ((Map<?, ?>)job.get("runs")).get("items"));
        assertEquals("boolean", ((Map<?, ?>)job.get("abba")).get("type"), "isAbba() is written too");
        assertTrue(props(schemas, "Run").containsKey("extSha"), "what it refers to is a component too");
    }

    /** */
    @Test void ignoredFieldsStayOutAndRecordsAreObjects() {
        Map<String, Object> schemas = new LinkedHashMap<>();
        OpenApi.schemaOf(Json.MAPPER.constructType(Plugins.Plugin.class), schemas);
        Map<String, Object> plugin = props(schemas, "Plugin");
        assertTrue(plugin.containsKey("module"));
        assertFalse(plugin.containsKey("jar"), "@JsonIgnore");
        assertFalse(plugin.containsKey("scenarios"), "@JsonIgnore");

        OpenApi.schemaOf(Json.MAPPER.constructType(PluginSchedule.Pairs.class), schemas);
        assertEquals(Map.of("$ref", "#/components/schemas/PluginSchedulePair"), props(schemas, "PluginSchedulePairs")
            .get("release"));
        assertEquals(List.of("extRef", "extSha", "igniteRef", "igniteSha"),
            props(schemas, "PluginSchedulePair").keySet().stream().sorted().toList());
    }

    /** The paths are the routes; a route without a description cannot be served. */
    @Test @SuppressWarnings("unchecked") void theDocumentIsMadeOfTheRoutes() {
        Map<String, Object> doc = OpenApi.build(List.of(new String[] {"GET", "/api/jobs/{id}"},
            new String[] {"POST", "/api/jobs/{id}/cancel"}), "1.0");
        assertEquals("3.1.0", doc.get("openapi"));
        Map<String, Object> paths = (Map<String, Object>)doc.get("paths");
        assertEquals(List.of("/api/jobs/{id}", "/api/jobs/{id}/cancel"), List.copyOf(paths.keySet()));

        Map<String, Object> get = (Map<String, Object>)((Map<String, Object>)paths.get("/api/jobs/{id}")).get("get");
        assertEquals("getJob", get.get("operationId"));
        assertEquals("read", get.get("x-firewatch-effect"));
        assertEquals(List.of(Map.of("name", "id", "in", "path", "required", true, "description", "the id",
            "schema", Map.of("type", "integer"))), get.get("parameters"), "a reading needs no name of who asks");

        Map<String, Object> cancel = (Map<String, Object>)((Map<String, Object>)paths.get("/api/jobs/{id}/cancel"))
            .get("post");
        assertEquals("destructive", cancel.get("x-firewatch-effect"));
        assertTrue(((List<Object>)cancel.get("parameters")).contains(Map.of("$ref", "#/components/parameters/User")),
            "what changes the stand says who asks");

        assertThrows(IllegalStateException.class,
            () -> OpenApi.build(List.<String[]>of(new String[] {"GET", "/api/nope"}), "1.0"));
    }
}
