package com.cesium.tak.ion.http;

import com.cesium.tak.ion.util.Redactor;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An outgoing GET, or a POST with a body (the OAuth token exchange). Header
 * values and bodies are never printed by {@link #toString()}.
 */
public final class HttpRequest {
    private final String method;
    private final URI uri;
    private final Map<String, String> headers;
    private final byte[] body;

    private HttpRequest(String method, URI uri, Map<String, String> headers, byte[] body) {
        this.method = method;
        this.uri = uri;
        this.headers = Collections.unmodifiableMap(headers);
        this.body = body;
    }

    public static Builder get(URI uri) {
        return new Builder("GET", uri, null);
    }

    /** A POST of {@code body} with the given {@code Content-Type}. */
    public static Builder post(URI uri, String contentType, byte[] body) {
        if (body == null) {
            throw new IllegalArgumentException("A POST needs a body.");
        }
        return new Builder("POST", uri, body.clone()).header("Content-Type", contentType);
    }

    public String method() {
        return method;
    }

    /** The request body, or {@code null} for a GET. */
    public byte[] body() {
        return body == null ? null : body.clone();
    }

    public URI uri() {
        return uri;
    }

    public Map<String, String> headers() {
        return headers;
    }

    @Override
    public String toString() {
        return method + " " + Redactor.redact(uri.toString()) + " headers=" + headers.keySet()
                + (body == null ? "" : " body=" + body.length + "B");
    }

    public static final class Builder {
        private final String method;
        private final URI uri;
        private final byte[] body;
        private final Map<String, String> headers = new LinkedHashMap<>();

        private Builder(String method, URI uri, byte[] body) {
            if (uri == null || !uri.isAbsolute()) {
                throw new IllegalArgumentException("An absolute request URI is required.");
            }
            this.method = method;
            this.uri = uri;
            this.body = body;
        }

        public Builder header(String name, String value) {
            if (containsLineBreak(name) || containsLineBreak(value)) {
                throw new IllegalArgumentException("HTTP header names and values cannot contain line breaks.");
            }
            headers.put(name, value);
            return this;
        }

        public HttpRequest build() {
            return new HttpRequest(method, uri, new LinkedHashMap<>(headers), body);
        }

        private static boolean containsLineBreak(String value) {
            return value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
        }
    }
}
