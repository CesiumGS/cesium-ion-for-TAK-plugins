package com.cesium.tak.ion;

import com.cesium.tak.ion.http.HttpRequest;
import com.cesium.tak.ion.http.HttpResponse;
import com.cesium.tak.ion.http.HttpTransport;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Scripted transport keyed by exact URI; unknown URIs answer 404. */
final class FakeTransport implements HttpTransport {
    final List<HttpRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> responses = new HashMap<>();

    FakeTransport respond(String uri, int status, String body) {
        return respond(uri, status, body, Collections.emptyMap());
    }

    FakeTransport respond(String uri, int status, String body, Map<String, List<String>> headers) {
        responses.put(uri, new Object[] {status, body, headers});
        return this;
    }

    FakeTransport fail(String uri) {
        responses.put(uri, new IOException("connection refused (fake)"));
        return this;
    }

    List<String> requestedUris() {
        List<String> uris = new ArrayList<>();
        for (HttpRequest request : requests) {
            uris.add(request.uri().toString());
        }
        return uris;
    }

    @Override
    @SuppressWarnings("unchecked")
    public HttpResponse send(HttpRequest request) throws IOException {
        requests.add(request);
        Object scripted = responses.get(request.uri().toString());
        if (scripted instanceof IOException) {
            throw (IOException) scripted;
        }
        if (scripted == null) {
            return new HttpResponse(404, request.uri(), Collections.emptyMap(), new byte[0]);
        }
        Object[] response = (Object[]) scripted;
        return new HttpResponse(
                (Integer) response[0],
                URI.create(request.uri().toString()),
                (Map<String, List<String>>) response[2],
                ((String) response[1]).getBytes(StandardCharsets.UTF_8));
    }
}
