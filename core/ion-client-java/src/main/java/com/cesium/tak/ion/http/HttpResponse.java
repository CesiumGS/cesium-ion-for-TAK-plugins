package com.cesium.tak.ion.http;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** A fully buffered HTTP response. Header names are matched case-insensitively. */
public final class HttpResponse {
    private final int status;
    private final URI finalUri;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    public HttpResponse(int status, URI finalUri, Map<String, List<String>> headers, byte[] body) {
        this.status = status;
        this.finalUri = finalUri;
        Map<String, List<String>> normalized = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                // HttpURLConnection reports the status line under a null key.
                if (entry.getKey() != null) {
                    normalized.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                }
            }
        }
        this.headers = Collections.unmodifiableMap(normalized);
        this.body = body == null ? new byte[0] : body;
    }

    public int status() {
        return status;
    }

    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }

    /** The URI that produced this response, after any redirects. */
    public URI finalUri() {
        return finalUri;
    }

    /** The first value of {@code name}, or {@code null}. */
    public String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public List<String> headerValues(String name) {
        List<String> values = headers.get(name);
        return values == null ? Collections.<String>emptyList() : values;
    }

    /** The body bytes. Callers must not modify the returned array. */
    public byte[] body() {
        return body;
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
