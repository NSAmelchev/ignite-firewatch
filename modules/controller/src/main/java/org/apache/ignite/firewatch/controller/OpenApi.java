package org.apache.ignite.firewatch.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

/**
 * The OpenAPI 3.1 document of the stand, served at {@code GET /api/openapi.json}. Its paths are the routes of the
 * {@link Router} as they are registered, so it never lags behind the code; what each one does is {@link ApiDocs}. The
 * schemas of the answers come from the classes the API serializes, as Jackson sees them.
 */
final class OpenApi {
    /** */
    private static final String REF = "#/components/schemas/";

    /** Names of nested classes in the document, where Outer+Inner would read badly. */
    private static final Map<Class<?>, String> NAMES = Map.of(
        Scenarios.Info.class, "Scenario",
        Plugins.Plugin.class, "Plugin",
        Log.Entry.class, "LogEntry",
        JobService.Hit.class, "SearchHit",
        JobService.TrendPoint.class, "TrendPoint",
        JobService.Noise.class, "Noise");

    /** What an agent needs before its first call. */
    private static final String ABOUT = String.join("\n",
        "Firewatch measures the performance of Apache Ignite: it builds commits, runs load phases (ABBA: A1 B1 B2 A2) "
            + "and tells whether B moved against A beyond the noise of the stand.",
        "",
        "- The stand is a shared measuring instrument: read freely, queue only what you were asked to. Every operation "
            + "says what it does in `x-firewatch-effect`: `read` changes nothing, `queue` puts jobs into the queue, "
            + "`change` changes the stand without a job, `destructive` stops or deletes work.",
        "- One job runs at a time. A stand closed for maintenance (`GET /api/health`: `worker.paused`) keeps queued "
            + "jobs waiting; `GET /api/jobs?filter=active` is the queue with its ETA.",
        "- There is no authentication. The header `X-Firewatch-User` names who queues or cancels a job (the name "
            + "URL-encoded, 2 to 40 letters, digits, spaces or `. _ @ -`, a letter or a digit first); Queue shows it. "
            + "Send a name of your own, such as `claude-agent`.",
        "- A ref is a sha, a branch, a tag or `pr/N`; `POST /api/resolve` says what it is.",
        "- An error is `{error, status}`: 400 a bad request, 404 nothing such, 409 the state of the stand does not "
            + "allow it now (it is cloning, the queue is busy), 500 a failure of the stand.");

    /** */
    private OpenApi() {
    }

    /**
     * Builds the document.
     *
     * @param routes {method, pattern} of every route.
     * @param version Version of the stand.
     * @return The document as a JSON tree.
     */
    static Map<String, Object> build(List<String[]> routes, String version) {
        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, Object> schemas = new LinkedHashMap<>();
        components.put("schemas", schemas);
        Map<String, Object> paths = new LinkedHashMap<>();
        Map<String, ApiDocs.Op> ops = ApiDocs.all();

        for (String[] r : routes) {
            ApiDocs.Op op = ops.get(r[0] + " " + r[1]);

            if (op == null)
                throw new IllegalStateException("no description of " + r[0] + " " + r[1] + " in ApiDocs");

            @SuppressWarnings("unchecked")
            Map<String, Object> path = (Map<String, Object>)paths.computeIfAbsent(r[1], k -> new LinkedHashMap<>());
            path.put(r[0].toLowerCase(), operation(r[1], op, schemas));
        }

        schemas.put("Error", Map.of("type", "object", "required", List.of("error", "status"), "properties",
            Map.of("error", Map.of("type", "string"), "status", Map.of("type", "integer"))));
        Map<String, Object> err = Map.of("description", "`{error, status}`: what went wrong",
            "content", Map.of("application/json", Map.of("schema", Map.of("$ref", REF + "Error"))));
        components.put("responses", Map.of("Error", err));
        components.put("parameters", Map.of("User", Map.of("name", Who.HEADER, "in", "header", "required", false,
            "description", "Who asks, URL-encoded: Queue shows it next to the jobs it queues or cancels",
            "schema", Map.of("type", "string"))));

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.1.0");
        doc.put("info", Map.of("title", "Firewatch", "version", version, "description", ABOUT,
            "license", Map.of("name", "Apache License 2.0", "identifier", "Apache-2.0")));
        doc.put("servers", List.of(Map.of("url", "/")));
        doc.put("security", List.of()); // no authentication: X-Firewatch-User only names who asks
        doc.put("tags", ApiDocs.tags());
        doc.put("paths", paths);
        doc.put("components", components);

        return doc;
    }

    /** One operation of a path. */
    private static Map<String, Object> operation(String pattern, ApiDocs.Op op, Map<String, Object> schemas) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operationId", op.id);
        m.put("tags", List.of(op.tag));
        m.put("summary", op.summary);
        m.put("description", (op.description == null ? "" : op.description + "\n\n") + op.effect.about);
        m.put("x-firewatch-effect", op.effect.name().toLowerCase());
        List<Object> params = new ArrayList<>();

        for (String seg : pattern.split("/")) {
            if (seg.startsWith("{") && seg.endsWith("}")) {
                String name = seg.substring(1, seg.length() - 1);
                params.add(Map.of("name", name, "in", "path", "required", true,
                    "description", ApiDocs.pathParam(name), "schema", Map.of("type", "id".equals(name) ? "integer"
                        : "string")));
            }
        }

        for (ApiDocs.Param p : op.query) {
            Map<String, Object> s = new LinkedHashMap<>(Map.of("type", p.type));

            if (p.def != null)
                s.put("default", p.def);

            params.add(Map.of("name", p.name, "in", "query", "required", false, "description", p.description,
                "schema", s));
        }

        if (op.effect != ApiDocs.Effect.READ)
            params.add(Map.of("$ref", "#/components/parameters/User"));

        if (!params.isEmpty())
            m.put("parameters", params);

        if (op.body != null)
            m.put("requestBody", Map.of("required", op.bodyRequired, "content",
                Map.of("application/json", Map.of("schema", op.body.schema(schemas)))));

        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put("200", Map.of("description", op.ret.description, "content",
            Map.of(op.ret.media, Map.of("schema", op.ret.schema(schemas)))));

        for (String code : List.of("400", "404", "409", "500"))
            responses.put(code, Map.of("$ref", "#/components/responses/Error"));

        m.put("responses", responses);

        return m;
    }

    /**
     * The schema of a Java type as Jackson writes it: a class of the stand becomes a component, referenced.
     *
     * @param t Type.
     * @param schemas Components so far: a new class is added.
     * @return Schema.
     */
    static Map<String, Object> schemaOf(JavaType t, Map<String, Object> schemas) {
        Class<?> c = t.getRawClass();

        if (c == String.class || c == Character.class || c == char.class)
            return Map.of("type", "string");

        if (c == int.class || c == Integer.class || c == short.class || c == Short.class)
            return Map.of("type", "integer");

        if (c == long.class || c == Long.class)
            return Map.of("type", "integer", "format", "int64");

        if (c == double.class || c == Double.class || c == float.class || c == Float.class)
            return Map.of("type", "number");

        if (c == boolean.class || c == Boolean.class)
            return Map.of("type", "boolean");

        if (c.isEnum()) {
            List<String> values = new ArrayList<>();

            for (Object e : c.getEnumConstants())
                values.add(e.toString());

            return Map.of("type", "string", "enum", values);
        }

        if (t.isArrayType() || t.isCollectionLikeType())
            return Map.of("type", "array", "items", schemaOf(t.getContentType(), schemas));

        if (t.isMapLikeType())
            return Map.of("type", "object", "additionalProperties", schemaOf(t.getContentType(), schemas));

        if (JsonNode.class.isAssignableFrom(c) || c == Object.class || !c.getName().startsWith("org.apache.ignite."))
            return Map.of("description", "any JSON");

        String name = nameOf(c);

        if (!schemas.containsKey(name)) {
            schemas.put(name, Map.of()); // a class that refers to itself finds its name taken
            BeanDescription bd = Json.MAPPER.getSerializationConfig().introspect(t);
            Map<String, Object> props = new LinkedHashMap<>();

            for (BeanPropertyDefinition p : bd.findProperties()) {
                if (p.couldSerialize())
                    props.put(p.getName(), withDescription(schemaOf(p.getPrimaryType(), schemas),
                        ApiDocs.field(c, p.getName())));
            }

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "object");
            s.put("properties", props);
            schemas.put(name, s);
        }

        return Map.of("$ref", REF + name);
    }

    /** */
    private static Map<String, Object> withDescription(Map<String, Object> s, String description) {
        if (description == null)
            return s;

        Map<String, Object> m = new LinkedHashMap<>(s);
        m.put("description", description);

        return m;
    }

    /** The name of a class in the document: nested ones are Outer+Inner unless {@link #NAMES} says better. */
    static String nameOf(Class<?> c) {
        String n = NAMES.get(c);

        if (n != null)
            return n;

        return c.getEnclosingClass() == null ? c.getSimpleName() : nameOf(c.getEnclosingClass()) + c.getSimpleName();
    }
}
