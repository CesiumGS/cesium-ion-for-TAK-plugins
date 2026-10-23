package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cesium.tak.ion.http.UrlConnectionTransport;
import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.model.AssetType;
import com.cesium.tak.ion.proxy.TileProxy;
import com.cesium.tak.ion.util.Secret;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TileProxyTest {
    private static final String SECRET = "test-secret-0123456789abcdef";

    private HttpServer upstream;
    private String upstreamBase;
    private final List<String> upstreamRequests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> acceptedToken = new AtomicReference<>("T1");
    private final AtomicInteger invalidations = new AtomicInteger();
    private final AtomicReference<String> currentToken = new AtomicReference<>("T1");
    private TileProxy proxy;

    @BeforeEach
    void start() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.createContext("/", this::serveUpstream);
        upstream.start();
        upstreamBase = "http://127.0.0.1:" + upstream.getAddress().getPort();

        proxy = new TileProxy(new TileProxy.EndpointSource() {
            @Override
            public AssetEndpoint endpoint(long assetId) {
                return assetId == 1 ? ionEndpoint() : googleEndpoint();
            }

            @Override
            public void invalidate(long assetId, AssetEndpoint stale) {
                invalidations.incrementAndGet();
                currentToken.set("T2");
            }
        }, new UrlConnectionTransport(), SECRET);
        proxy.start(0, 4);
        proxy.register(1);
        proxy.register(2);
    }

    private AssetEndpoint ionEndpoint() {
        return new AssetEndpoint.IonHosted(1, AssetType.TILES_3D, upstreamBase + "/ion/1/tileset.json",
                Collections.emptyMap(), Secret.of(currentToken.get()), Collections.emptyList(), -1);
    }

    private AssetEndpoint googleEndpoint() {
        return new AssetEndpoint.External3DTiles(2, AssetType.TILES_3D, upstreamBase + "/v1/3dtiles/root.json",
                Map.of("key", Secret.of("GKEY")), Collections.emptyList());
    }

    @AfterEach
    void stop() {
        proxy.close();
        upstream.stop(0);
    }

    private void serveUpstream(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().toString();
        upstreamRequests.add(uri);
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
        if (path.startsWith("/ion/")) {
            if (!query.contains("access_token=" + acceptedToken.get())) {
                reply(exchange, 401, "text/plain", "expired".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/ion/1/tileset.json")) {
                reply(exchange, 200, "application/json", ("{\"root\":{\"children\":["
                        + "{\"content\":{\"uri\":\"0/0.b3dm\"}},"
                        + "{\"content\":{\"uri\":\"/ion/1/abs.json\"}},"
                        + "{\"content\":{\"uri\":\"" + upstreamBase + "/ion/1/full.json?access_token=LEAK\"}},"
                        + "{\"content\":{\"uri\":\"https://elsewhere.example.com/x.glb\"}}]}}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            reply(exchange, 200, "application/octet-stream", TilesTest.glb("{\"asset\":{\"copyright\":\"Ion\"}}"));
            return;
        }
        if (path.startsWith("/v1/3dtiles/")) {
            if (!query.contains("key=GKEY")) {
                reply(exchange, 403, "text/plain", "no key".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.endsWith("root.json")) {
                reply(exchange, 200, "application/json",
                        "{\"root\":{\"content\":{\"uri\":\"/v1/3dtiles/datasets/A/child.json?session=S1\"}},\"geometricError\":1e+100}"
                                .getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!query.contains("session=S1")) {
                reply(exchange, 403, "text/plain", "no session".getBytes(StandardCharsets.UTF_8));
                return;
            }
            reply(exchange, 200, "application/octet-stream", TilesTest.glb("{\"asset\":{\"copyright\":\"Google\"}}"));
            return;
        }
        reply(exchange, 404, "text/plain", new byte[0]);
    }

    @Test
    void forwardsWithCredentialsAndKeepsReferencesInsideTheProxy() throws IOException {
        String root = proxy.proxyUrl(ionEndpoint(), upstreamBase + "/ion/1/tileset.json?access_token=SHOULD_BE_DROPPED&v=2");
        assertTrue(root.endsWith("/ion/1/tileset.json?v=2"), root);
        assertFalse(root.contains("access_token"), root);
        Fetched fetched = fetch(root);
        assertEquals(200, fetched.status);
        String prefix = "/" + SECRET + "/a/1";
        assertTrue(fetched.body.contains("\"uri\":\"0/0.b3dm\""), "relative references stay relative");
        assertTrue(fetched.body.contains("\"uri\":\"" + prefix + "/ion/1/abs.json\""), fetched.body);
        assertTrue(fetched.body.contains("\"uri\":\"" + prefix + "/ion/1/full.json\""), "same-origin URL proxied, credential dropped");
        assertTrue(fetched.body.contains("https://elsewhere.example.com/x.glb"), "other origins untouched");
        assertFalse(fetched.body.contains("LEAK"));
        assertTrue(upstreamRequests.get(0).contains("access_token=T1"));
        assertFalse(upstreamRequests.get(0).contains("SHOULD_BE_DROPPED"));
    }

    @Test
    void googleStyleAbsolutePathsKeepSessionAndGetTheKey() throws IOException {
        Fetched root = fetch(proxy.rootUrl(googleEndpoint()));
        assertEquals(200, root.status);
        String child = "/" + SECRET + "/a/2/v1/3dtiles/datasets/A/child.json?session=S1";
        assertTrue(root.body.contains("\"uri\":\"" + child + "\""), root.body);
        assertTrue(root.body.contains("1e+100"), "numbers pass through untouched");

        List<byte[]> tiles = new CopyOnWriteArrayList<>();
        proxy.setTileListener((assetId, content) -> tiles.add(content));
        Fetched leaf = fetch("http://127.0.0.1:" + proxy.port() + child);
        assertEquals(200, leaf.status);
        assertEquals(1, tiles.size(), "GLB tiles reach the listener (for Google credits)");
        String last = upstreamRequests.get(upstreamRequests.size() - 1);
        assertTrue(last.contains("session=S1") && last.contains("key=GKEY"), last);
    }

    @Test
    void refreshesRejectedEndpointTokensOnce() throws IOException {
        acceptedToken.set("T2");
        Fetched fetched = fetch(proxy.rootUrl(ionEndpoint()));
        assertEquals(200, fetched.status);
        assertEquals(1, invalidations.get());
        assertTrue(upstreamRequests.get(upstreamRequests.size() - 1).contains("access_token=T2"));
    }

    @Test
    void persistentUpstreamRejectionsBecomeBadGatewayNotAuthPrompts() throws IOException {
        acceptedToken.set("NEVER");
        Fetched fetched = fetch(proxy.rootUrl(ionEndpoint()));
        assertEquals(502, fetched.status);
        assertEquals(1, invalidations.get(), "refreshed once before giving up");
    }

    @Test
    void refusesWrongSecretUnregisteredAssetsAndOtherMethods() throws IOException {
        assertEquals(404, fetch("http://127.0.0.1:" + proxy.port() + "/wrong-secret-0000000000000/a/1/ion/1/tileset.json").status);
        // 404, never 403: ATAK answers a 403 from a map source with a login dialog.
        assertEquals(404, fetch(proxy.baseUrl() + "/a/99/x.json").status);
        proxy.unregister(2);
        assertEquals(404, fetch(proxy.rootUrl(googleEndpoint())).status);
        assertEquals(404, fetch(proxy.baseUrl() + "/other").status);
        HttpURLConnection post = (HttpURLConnection) new URL(proxy.rootUrl(ionEndpoint())).openConnection();
        post.setRequestMethod("POST");
        post.setDoOutput(true);
        post.getOutputStream().close();
        assertEquals(405, post.getResponseCode());
        assertTrue(upstreamRequests.isEmpty(), "refused requests never reach upstream");
    }

    @Test
    void requestPathsNeverChooseTheUpstreamHost() throws IOException {
        // base + "//host/..." would read as a network-path reference to another host.
        int status = fetch(proxy.baseUrl() + "/a/1//elsewhere.example.com/x.glb").status;
        assertTrue(status == 400 || status == 404, "refused: " + status);
        assertTrue(upstreamRequests.isEmpty(), "never forwarded");
    }

    @Test
    void imageryTemplatesKeepTheirPlaceholders() {
        String template = proxy.proxyUrl(ionEndpoint(), upstreamBase + "/ion/1/{z}/{x}/{y}.png?access_token=T1");
        assertEquals(proxy.baseUrl() + "/a/1/ion/1/{z}/{x}/{y}.png", template);
    }

    @Test
    void reportsEachRequestWithoutItsQuery() throws IOException {
        List<String> seen = new CopyOnWriteArrayList<>();
        proxy.setRequestListener((assetId, path, status, bytes, millis, detail) ->
                seen.add(assetId + " " + path + " " + status + " " + detail));
        fetch(proxy.rootUrl(ionEndpoint()));
        proxy.unregister(2);
        fetch(proxy.rootUrl(googleEndpoint()));
        assertEquals("1 /ion/1/tileset.json 200 null", seen.get(0));
        assertEquals("2 /v1/3dtiles/root.json 404 Asset not enabled in the plugin.", seen.get(1));
    }

    @Test
    void bindsToLoopbackOnly() {
        assertTrue(proxy.baseUrl().startsWith("http://127.0.0.1:"));
    }

    private static final class Fetched {
        final int status;
        final String body;

        Fetched(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static Fetched fetch(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new Fetched(status, body);
    }

    private static void reply(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }
}
