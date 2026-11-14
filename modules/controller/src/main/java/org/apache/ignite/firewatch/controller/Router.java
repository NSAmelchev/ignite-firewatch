package org.apache.ignite.firewatch.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/** Tiny JSON router on top of {@code jdk.httpserver}: path patterns with {@code {param}} segments. */
public final class Router implements HttpHandler {
    /** Handler of a request. */
    @FunctionalInterface
    public interface Handler {
        /**
         * Returns a {@link Response}, or any object to be sent as JSON (null = 204).
         *
         * @param req Request.
         * @return Response, or an object to send as JSON.
         */
        Object handle(Req req) throws Exception;
    }

    /** Error that is sent to the client with its HTTP status. */
    public static final class HttpError extends RuntimeException {
        /** */
        public final int status;

        /**
         * Creates the error.
         *
         * @param status HTTP status.
         * @param msg Message for the client.
         */
        public HttpError(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }

    /** HTTP response: status, headers and a body held in memory or streamed from a file. */
    public static final class Response {
        /** */
        public int status = 200;

        /** */
        public String contentType = "application/json";

        /** */
        public byte[] body = new byte[0];

        /** When set, the body is streamed from this file instead of {@link #body}. */
        public java.nio.file.Path file;

        /** When set, writes the body as it goes, of a length nobody knows in advance (a zip made on the fly). */
        public Body stream;

        /** */
        public Map<String, String> headers = new LinkedHashMap<>();

        /**
         * Creates a response whose body is streamed from a file.
         *
         * @param contentType Content type.
         * @param file File with the body.
         * @return Response.
         */
        public static Response file(String contentType, java.nio.file.Path file) {
            Response r = new Response();
            r.contentType = contentType;
            r.file = file;

            return r;
        }

        /**
         * Creates a response whose body is written as it is made: the client gets it chunked.
         *
         * @param contentType Content type.
         * @param body Writer of the body.
         * @return Response.
         */
        public static Response stream(String contentType, Body body) {
            Response r = new Response();
            r.contentType = contentType;
            r.stream = body;

            return r;
        }

        /**
         * Makes the browser save the body as a file instead of showing it.
         *
         * @param name File name.
         * @return This response.
         */
        public Response attachment(String name) {
            headers.put("Content-Disposition", "attachment; filename=\"" + name.replaceAll("[^A-Za-z0-9._+-]", "_") + "\"");

            return this;
        }

        /**
         * Creates a response with a text body.
         *
         * @param status HTTP status.
         * @param contentType Content type.
         * @param body Body text, sent as UTF-8.
         * @return Response.
         */
        public static Response text(int status, String contentType, String body) {
            Response r = new Response();
            r.status = status;
            r.contentType = contentType;
            r.body = body.getBytes(StandardCharsets.UTF_8);

            return r;
        }

        /**
         * Creates a response with a binary body.
         *
         * @param contentType Content type.
         * @param body Body bytes.
         * @return Response.
         */
        public static Response bytes(String contentType, byte[] body) {
            Response r = new Response();
            r.contentType = contentType;
            r.body = body;

            return r;
        }
    }

    /** Writes the body of a {@link Response#stream} response. */
    @FunctionalInterface
    public interface Body {
        /**
         * Writes the body.
         *
         * @param out Body of the response; closed by the caller.
         * @throws IOException If writing fails.
         */
        void write(OutputStream out) throws IOException;
    }

    /** HTTP request with its path parameters, query and body. */
    public static final class Req {
        /** */
        public final HttpExchange ex;

        /** */
        public final Map<String, String> params;

        /** */
        public final Map<String, String> query;

        /** */
        private byte[] body;

        /** */
        Req(HttpExchange ex, Map<String, String> params) {
            this.ex = ex;
            this.params = params;
            this.query = parseQuery(ex.getRequestURI().getRawQuery());
        }

        /**
         * Gets a path parameter.
         *
         * @param name Parameter name.
         * @return Value, or {@code null} if there is none.
         */
        public String param(String name) {
            return params.get(name);
        }

        /**
         * Gets a path parameter as a number.
         *
         * @param name Parameter name.
         * @return Value.
         */
        public long paramLong(String name) {
            try {
                return Long.parseLong(params.get(name));
            }
            catch (NumberFormatException e) {
                throw new HttpError(400, name + " must be a number");
            }
        }

        /**
         * Gets a query parameter.
         *
         * @param name Parameter name.
         * @param def Value to use if the parameter is missing or empty.
         * @return Value.
         */
        public String query(String name, String def) {
            String v = query.get(name);

            return v == null || v.isEmpty() ? def : v;
        }

        /**
         * Gets a query parameter as a number.
         *
         * @param name Parameter name.
         * @param def Value to use if the parameter is missing or empty.
         * @return Value.
         */
        public int queryInt(String name, int def) {
            String v = query.get(name);

            if (v == null || v.isEmpty())
                return def;

            try {
                return Integer.parseInt(v);
            }
            catch (NumberFormatException e) {
                throw new HttpError(400, name + " must be a number");
            }
        }

        /**
         * Reads the request body once and keeps it.
         *
         * @return Body bytes.
         */
        public byte[] body() throws IOException {
            if (body == null) {
                try (InputStream in = ex.getRequestBody()) {
                    body = in.readAllBytes();
                }
            }

            return body;
        }

        /**
         * Request body as a JSON object (an empty body is an empty object).
         *
         * @return JSON object.
         */
        public JsonNode json() throws IOException {
            byte[] b = body();

            if (b.length == 0)
                return Json.MAPPER.createObjectNode();

            JsonNode n = Json.tree(new String(b, StandardCharsets.UTF_8));

            if (!n.isObject())
                throw new HttpError(400, "a JSON object is expected");

            return n;
        }

        /**
         * Gets a text field of a JSON object.
         *
         * @param n JSON object.
         * @param field Field name.
         * @param def Value to use if the field is missing or null.
         * @return Value.
         */
        public static String str(JsonNode n, String field, String def) {
            JsonNode v = n.get(field);

            return v == null || v.isNull() ? def : v.asText();
        }

        /**
         * Gets a numeric field of a JSON object.
         *
         * @param n JSON object.
         * @param field Field name.
         * @param def Value to use if the field is missing or null.
         * @return Value.
         */
        public static int integer(JsonNode n, String field, int def) {
            JsonNode v = n.get(field);

            if (v == null || v.isNull())
                return def;

            if (v.isInt() || v.isLong())
                return v.asInt();

            try {
                return Integer.parseInt(v.asText());
            }
            catch (NumberFormatException e) {
                throw new HttpError(400, field + " must be a number");
            }
        }
    }

    /** Route: a method and a path pattern with its handler. */
    private static final class Route {
        /** */
        final String method;

        /** */
        final String[] segments;

        /** */
        final Handler handler;

        /** */
        Route(String method, String pattern, Handler handler) {
            this.method = method;
            this.segments = split(pattern);
            this.handler = handler;
        }

        /** */
        Map<String, String> match(String[] path) {
            if (path.length != segments.length)
                return null;

            Map<String, String> params = new HashMap<>();

            for (int i = 0; i < segments.length; i++) {
                String s = segments[i];

                if (s.startsWith("{") && s.endsWith("}"))
                    params.put(s.substring(1, s.length() - 1), path[i]);
                else if (!s.equals(path[i]))
                    return null;
            }

            return params;
        }
    }

    /** */
    private final List<Route> routes = new ArrayList<>();

    /**
     * The routes as they were registered: what the OpenAPI document of the stand is made of, see {@link OpenApi}.
     *
     * @return {method, pattern} of each.
     */
    List<String[]> routes() {
        return routes.stream().map(r -> new String[] {r.method, "/" + String.join("/", r.segments)}).toList();
    }

    /**
     * Registers a GET handler.
     *
     * @param pattern Path pattern.
     * @param h Handler.
     */
    public void get(String pattern, Handler h) {
        routes.add(new Route("GET", pattern, h));
    }

    /**
     * Registers a POST handler.
     *
     * @param pattern Path pattern.
     * @param h Handler.
     */
    public void post(String pattern, Handler h) {
        routes.add(new Route("POST", pattern, h));
    }

    /**
     * Registers a PUT handler.
     *
     * @param pattern Path pattern.
     * @param h Handler.
     */
    public void put(String pattern, Handler h) {
        routes.add(new Route("PUT", pattern, h));
    }

    /**
     * Registers a DELETE handler.
     *
     * @param pattern Path pattern.
     * @param h Handler.
     */
    public void delete(String pattern, Handler h) {
        routes.add(new Route("DELETE", pattern, h));
    }

    /** {@inheritDoc} */
    @Override public void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String[] path = split(ex.getRequestURI().getPath());
        boolean pathMatched = false;
        Who.set(Who.fromHeader(ex.getRequestHeaders().getFirst(Who.HEADER))); // the pool reuses threads: cleared below

        try {
            for (Route r : routes) {
                Map<String, String> params = r.match(path);

                if (params == null)
                    continue;

                pathMatched = true;

                if (!r.method.equals(method))
                    continue;

                Object result = r.handler.handle(new Req(ex, params));

                if (result instanceof Response)
                    send(ex, (Response)result);
                else if (result == null)
                    send(ex, Response.text(204, "text/plain", ""));
                else
                    send(ex, Response.text(200, "application/json", Json.write(result)));

                return;
            }

            throw new HttpError(pathMatched ? 405 : 404, pathMatched ? "method not allowed" : "no such endpoint");
        }
        catch (HttpError e) {
            error(ex, e.status, e.getMessage());
        }
        catch (IllegalArgumentException e) {
            error(ex, 400, e.getMessage());
        }
        catch (NoSuchElementException e) {
            error(ex, 404, e.getMessage());
        }
        catch (IllegalStateException e) {
            error(ex, 409, e.getMessage());
        }
        catch (Exception e) {
            Log.error(method + " " + ex.getRequestURI() + " failed", e);
            error(ex, 500, e.toString());
        }
        finally {
            Who.set(null);
        }
    }

    /** */
    private static void error(HttpExchange ex, int status, String msg) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", msg == null ? "error" : msg);
        body.put("status", status);
        send(ex, Response.text(status, "application/json", Json.write(body)));
    }

    /**
     * Sends a response to the client.
     *
     * @param ex Exchange to answer.
     * @param r Response.
     */
    public static void send(HttpExchange ex, Response r) throws IOException {
        ex.getResponseHeaders().set("Content-Type", r.contentType + (r.contentType.startsWith("text/")
            || r.contentType.equals("application/json") || r.contentType.contains("javascript") ? "; charset=utf-8"
            : ""));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        r.headers.forEach((k, v) -> ex.getResponseHeaders().set(k, v));

        if (r.status == 204 || "HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(r.status, -1);
            ex.close();

            return;
        }

        if (r.stream != null) {
            ex.sendResponseHeaders(r.status, 0);

            try (OutputStream out = ex.getResponseBody()) {
                r.stream.write(out);
            }
            catch (IOException e) {
                // the headers are gone, so is the chance to say it to the client: it gets a cut body
                Log.warn(ex.getRequestURI() + ": the body broke off: " + e.getMessage());
            }

            ex.close();

            return;
        }

        if (r.file != null) {
            long len = java.nio.file.Files.size(r.file);
            ex.sendResponseHeaders(r.status, len == 0 ? -1 : len);

            if (len > 0)
                try (OutputStream out = ex.getResponseBody()) {
                    java.nio.file.Files.copy(r.file, out);
                }

            ex.close();

            return;
        }

        ex.sendResponseHeaders(r.status, r.body.length == 0 ? -1 : r.body.length);

        if (r.body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(r.body);
            }
        }

        ex.close();
    }

    /** */
    static String[] split(String path) {
        List<String> out = new ArrayList<>();

        for (String s : path.split("/")) {
            if (!s.isEmpty())
                out.add(s);
        }

        return out.toArray(new String[0]);
    }

    /** */
    static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new LinkedHashMap<>();

        if (q == null || q.isEmpty())
            return m;

        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            String v = i < 0 ? "" : kv.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }

        return m;
    }
}
