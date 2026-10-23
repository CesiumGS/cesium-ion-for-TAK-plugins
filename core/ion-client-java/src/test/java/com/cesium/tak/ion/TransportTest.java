package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cesium.tak.ion.http.HttpRequest;
import com.cesium.tak.ion.http.HttpResponse;
import com.cesium.tak.ion.http.UrlConnectionTransport;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the real HttpURLConnection transport against loopback servers. */
class TransportTest {
    private HttpServer primary;
    private HttpServer other;
    private final AtomicReference<String> otherAuthorization = new AtomicReference<>("unset");
    private final AtomicReference<String> primaryAuthorization = new AtomicReference<>("unset");

    @BeforeEach
    void start() throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        other = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        other.createContext("/landing", exchange -> {
            otherAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, 200, "other");
        });
        other.start();

        primary = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        primary.createContext("/same", exchange -> {
            exchange.getResponseHeaders().add("Location", "/landing");
            reply(exchange, 302, "");
        });
        primary.createContext("/landing", exchange -> {
            primaryAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, 200, "primary");
        });
        primary.createContext("/cross", exchange -> {
            exchange.getResponseHeaders().add("Location", base(other) + "/landing");
            reply(exchange, 307, "");
        });
        primary.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            reply(exchange, 302, "");
        });
        primary.createContext("/gzip", exchange -> {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write("{\"zipped\":true}".getBytes(StandardCharsets.UTF_8));
            }
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            byte[] body = compressed.toByteArray();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        primary.createContext("/big", exchange -> reply(exchange, 200, "x".repeat(2048)));
        primary.createContext("/error", exchange -> reply(exchange, 500, "{\"message\":\"boom\"}"));
        primary.start();
    }

    @AfterEach
    void stop() {
        primary.stop(0);
        other.stop(0);
    }

    @Test
    void keepsAuthorizationOnSameOriginRedirect() throws IOException {
        HttpResponse response = send("/same");
        assertEquals(200, response.status());
        assertEquals("primary", response.bodyText());
        assertEquals("Bearer secret-token", primaryAuthorization.get());
        assertTrue(response.finalUri().toString().endsWith("/landing"));
    }

    @Test
    void dropsAuthorizationOnCrossOriginRedirect() throws IOException {
        HttpResponse response = send("/cross");
        assertEquals("other", response.bodyText());
        assertNull(otherAuthorization.get(), "credentials must not follow a redirect to another origin");
    }

    @Test
    void stopsRedirectLoops() {
        IOException failure = assertThrows(IOException.class, () -> send("/loop"));
        assertTrue(failure.getMessage().contains("Too many redirects"));
    }

    @Test
    void decompressesGzip() throws IOException {
        assertEquals("{\"zipped\":true}", send("/gzip").bodyText());
    }

    @Test
    void enforcesBodyLimit() {
        UrlConnectionTransport small = new UrlConnectionTransport(5_000, 5_000, 1024, null, "test");
        assertThrows(IOException.class, () -> small.send(HttpRequest.get(URI.create(base(primary) + "/big")).build()));
    }

    @Test
    void returnsErrorResponsesWithBodies() throws IOException {
        HttpResponse response = send("/error");
        assertEquals(500, response.status());
        assertEquals("{\"message\":\"boom\"}", response.bodyText());
    }

    @Test
    void requestToStringHidesHeaderValuesAndRedactsUrl() {
        HttpRequest request = HttpRequest.get(URI.create("https://tile.googleapis.com/a.glb?key=K&session=S"))
                .header("Authorization", "Bearer secret-token")
                .build();
        String description = request.toString();
        assertTrue(!description.contains("secret-token") && !description.contains("=K") && !description.contains("=S"),
                description);
    }

    @Test
    void rejectsHeaderInjection() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpRequest.get(URI.create("https://example.com/")).header("X-Test", "a\r\nInjected: b"));
    }

    private HttpResponse send(String path) throws IOException {
        return new UrlConnectionTransport().send(HttpRequest.get(URI.create(base(primary) + path))
                .header("Authorization", "Bearer secret-token")
                .build());
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
        exchange.close();
    }
}
