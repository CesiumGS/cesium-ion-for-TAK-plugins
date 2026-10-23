package com.cesium.tak.ion.proxy;

import com.cesium.tak.ion.IonException;
import com.cesium.tak.ion.http.HttpRequest;
import com.cesium.tak.ion.http.HttpResponse;
import com.cesium.tak.ion.http.HttpTransport;
import com.cesium.tak.ion.json.JsonException;
import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.tiles.TileContent;
import com.cesium.tak.ion.tiles.TileUrls;
import com.cesium.tak.ion.tiles.TilesetUriRewriter;
import com.cesium.tak.ion.util.Redactor;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A loopback HTTP proxy that lets a renderer stream ion content through
 * credential-free URLs.
 *
 * <p>A renderer is given {@code http://127.0.0.1:<port>/<secret>/a/<assetId>/<path>}
 * URLs. Each request is forwarded to the asset endpoint's own origin with the
 * endpoint's current credentials, so tokens never reach the renderer, its
 * caches, or the files it persists. Endpoint tokens are refreshed when they
 * expire or are rejected.
 *
 * <p>Security: bound to the loopback interface only; every path carries a
 * per-install secret (compared in constant time); only registered assets are
 * served; upstream requests never leave the endpoint's origin. Tileset JSON
 * references that would escape the proxy (absolute paths, same-origin
 * absolute URLs) are rewritten back into it.
 */
public final class TileProxy implements Closeable {
    /** Supplies (cached) endpoints; usually backed by {@link com.cesium.tak.ion.EndpointCache}. */
    public interface EndpointSource {
        AssetEndpoint endpoint(long assetId) throws IonException;

        /** The endpoint's credentials were rejected; fetch a fresh one next time. */
        void invalidate(long assetId, AssetEndpoint stale);
    }

    /** Receives each GLB-based tile served, e.g. to collect Google credits. */
    public interface TileListener {
        void onTile(long assetId, byte[] content);
    }

    /** Observes each request for an asset, for diagnostics. */
    public interface RequestListener {
        /**
         * @param path the upstream path, without the query (which may hold credentials)
         * @param detail for failures, the start of the response text (redacted); otherwise {@code null}
         */
        void onRequest(long assetId, String path, int status, int bytes, long millis, String detail);
    }

    static final int MAX_REQUEST_HEAD_BYTES = 16 * 1024;
    static final int SOCKET_TIMEOUT_MS = 60_000;

    private final EndpointSource endpoints;
    private final HttpTransport upstream;
    private final String secret;
    private final Set<Long> allowedAssets = ConcurrentHashMap.newKeySet();
    private volatile TileListener tileListener;
    private volatile RequestListener requestListener;
    private ServerSocket server;
    private ExecutorService workers;
    private Thread acceptor;
    private volatile boolean running;

    /**
     * @param secret an unguessable URL-safe string, stable across restarts so
     *     layers the renderer persisted keep working
     */
    public TileProxy(EndpointSource endpoints, HttpTransport upstream, String secret) {
        if (secret == null || secret.length() < 16 || !secret.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("The proxy secret must be at least 16 URL-safe characters.");
        }
        this.endpoints = endpoints;
        this.upstream = upstream;
        this.secret = secret;
    }

    /**
     * Starts listening on 127.0.0.1, preferring {@code preferredPort} (so
     * persisted layer URLs stay valid) and falling back to any free port.
     *
     * @return the port in use
     */
    public synchronized int start(int preferredPort, int threads) throws IOException {
        if (running) {
            return server.getLocalPort();
        }
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        try {
            socket.bind(new InetSocketAddress(loopback, preferredPort));
        } catch (IOException portInUse) {
            socket.close();
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress(loopback, 0));
        }
        server = socket;
        workers = Executors.newFixedThreadPool(Math.max(1, threads), runnable -> {
            Thread thread = new Thread(runnable, "ion-tile-proxy");
            thread.setDaemon(true);
            return thread;
        });
        running = true;
        acceptor = new Thread(this::acceptLoop, "ion-tile-proxy-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        return server.getLocalPort();
    }

    public int port() {
        ServerSocket current = server;
        return current == null ? -1 : current.getLocalPort();
    }

    /** {@code http://127.0.0.1:<port>/<secret>}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + port() + "/" + secret;
    }

    /** Allows requests for {@code assetId}; unregistered assets are refused. */
    public void register(long assetId) {
        allowedAssets.add(assetId);
    }

    public void unregister(long assetId) {
        allowedAssets.remove(assetId);
    }

    public void setTileListener(TileListener listener) {
        this.tileListener = listener;
    }

    public void setRequestListener(RequestListener listener) {
        this.requestListener = listener;
    }

    /**
     * The proxy URL for {@code upstreamUrl}, which must be on the endpoint's
     * origin. The endpoint's own credential parameters are dropped (the proxy
     * re-adds current ones); other parameters, such as a Google tile
     * {@code session}, are kept. Implicit-tiling templates and placeholders
     * such as {@code {z}} pass through unchanged.
     */
    public String proxyUrl(AssetEndpoint endpoint, String upstreamUrl) {
        return assetPrefix(endpoint.assetId()) + TileUrls.pathAndQuery(withoutEndpointCredentials(endpoint, upstreamUrl));
    }

    /** The proxy URL of the endpoint's root document (tileset JSON, terrain or imagery base). */
    public String rootUrl(AssetEndpoint endpoint) {
        return proxyUrl(endpoint, endpoint.url());
    }

    private static String withoutEndpointCredentials(AssetEndpoint endpoint, String url) {
        return TileUrls.withoutQueryParameters(url, endpoint.credentials().keySet());
    }

    private String assetPrefix(long assetId) {
        return baseUrl() + "/a/" + assetId;
    }

    private String assetPathPrefix(long assetId) {
        return "/" + secret + "/a/" + assetId;
    }

    @Override
    public synchronized void close() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
        if (workers != null) {
            workers.shutdownNow();
            try {
                workers.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = server.accept();
                workers.execute(() -> handle(client));
            } catch (SocketException closed) {
                return;
            } catch (IOException | RuntimeException failure) {
                if (!running) {
                    return;
                }
            }
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            Request request = Request.read(new BufferedInputStream(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();
            if (request == null) {
                respond(out, 400, "text/plain", "Bad request".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            Response response = route(request);
            respond(out, response.status, response.contentType, response.body, request.head);
        } catch (IOException | RuntimeException ignored) {
            // The client went away or sent garbage; nothing to report to it.
        }
    }

    Response route(Request request) {
        if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
            return Response.text(405, "Only GET and HEAD are supported.");
        }
        // /<secret>/a/<assetId>/<path...>
        String target = request.target;
        if (!target.startsWith("/")) {
            return Response.text(404, "Not found.");
        }
        int secretEnd = target.indexOf('/', 1);
        if (secretEnd < 0 || !constantTimeEquals(target.substring(1, secretEnd), secret)) {
            return Response.text(404, "Not found.");
        }
        String rest = target.substring(secretEnd);
        if (!rest.startsWith("/a/")) {
            return Response.text(404, "Not found.");
        }
        int idEnd = rest.indexOf('/', 3);
        if (idEnd < 0) {
            return Response.text(404, "Not found.");
        }
        long assetId;
        try {
            assetId = Long.parseLong(rest.substring(3, idEnd));
        } catch (NumberFormatException invalid) {
            return Response.text(404, "Not found.");
        }
        String upstreamPathAndQuery = rest.substring(idEnd);
        long started = System.nanoTime();
        Response response = allowedAssets.contains(assetId)
                ? forward(assetId, upstreamPathAndQuery)
                : Response.text(404, "Asset not enabled in the plugin.");
        report(assetId, upstreamPathAndQuery, response, (System.nanoTime() - started) / 1_000_000L);
        return response;
    }

    private void report(long assetId, String pathAndQuery, Response response, long millis) {
        RequestListener listener = requestListener;
        if (listener == null) {
            return;
        }
        int query = pathAndQuery.indexOf('?');
        String path = query < 0 ? pathAndQuery : pathAndQuery.substring(0, query);
        String detail = null;
        if (response.status >= 400) {
            int length = Math.min(response.body.length, 200);
            detail = Redactor.redact(new String(response.body, 0, length, StandardCharsets.UTF_8)
                    .replace('\r', ' ').replace('\n', ' ').trim());
        }
        try {
            listener.onRequest(assetId, path, response.status, response.body.length, millis, detail);
        } catch (RuntimeException ignored) {
            // Diagnostics must never break serving.
        }
    }

    private Response forward(long assetId, String pathAndQuery) {
        for (int attempt = 0; attempt < 2; attempt++) {
            AssetEndpoint endpoint;
            try {
                endpoint = endpoints.endpoint(assetId);
            } catch (IonException failure) {
                // Never 401/403: TAK clients cannot supply credentials, and
                // ATAK answers them with a login dialog for the map source.
                int status = failure.kind() == IonException.Kind.NOT_FOUND ? 404 : 502;
                return Response.text(status, failure.getMessage());
            }
            String base = TileUrls.originBase(endpoint.url());
            if (base == null) {
                return Response.text(502, "The asset has no streamable endpoint.");
            }
            String upstreamUrl = endpoint.authorize(base + pathAndQuery);
            // The request supplies only a path and query: the upstream request
            // always goes to the asset endpoint's own origin.
            if (!pathAndQuery.startsWith("/") || !base.equals(TileUrls.originBase(upstreamUrl))) {
                return Response.text(404, "Not found.");
            }
            HttpResponse response;
            try {
                response = upstream.send(HttpRequest.get(URI.create(upstreamUrl)).header("Accept", "*/*").build());
            } catch (IOException | IllegalArgumentException failure) {
                return Response.text(502, "Upstream request failed: " + Redactor.redact(String.valueOf(failure.getMessage())));
            }
            if ((response.status() == 401 || response.status() == 403) && attempt == 0) {
                endpoints.invalidate(assetId, endpoint);
                continue;
            }
            return translate(assetId, endpoint, response);
        }
        return Response.text(502, "Upstream rejected refreshed credentials.");
    }

    private Response translate(long assetId, AssetEndpoint endpoint, HttpResponse response) {
        String contentType = response.header("Content-Type");
        byte[] body = response.body();
        if (response.status() == 401 || response.status() == 403) {
            return Response.text(502, "Upstream rejected refreshed credentials (" + response.status() + ").");
        }
        if (!response.isSuccessful()) {
            return new Response(response.status(), contentType == null ? "application/octet-stream" : contentType, body);
        }
        TileContent.Format format = TileContent.sniff(body);
        if (format == TileContent.Format.JSON) {
            try {
                String rewritten = rewriteTileset(assetId, endpoint, new String(body, StandardCharsets.UTF_8));
                return new Response(200, "application/json", rewritten.getBytes(StandardCharsets.UTF_8));
            } catch (JsonException notATileset) {
                return new Response(200, contentType == null ? "application/json" : contentType, body);
            }
        }
        TileListener listener = tileListener;
        if (listener != null && (format == TileContent.Format.GLB || format == TileContent.Format.B3DM)) {
            listener.onTile(assetId, body);
        }
        return new Response(200, contentType == null ? "application/octet-stream" : contentType, body);
    }

    /** Keeps every reference inside the proxy; leaves relative ones alone (they already are). */
    String rewriteTileset(long assetId, AssetEndpoint endpoint, String json) throws JsonException {
        String endpointBase = TileUrls.originBase(endpoint.url());
        String pathPrefix = assetPathPrefix(assetId);
        return TilesetUriRewriter.rewrite(json, reference -> {
            if (reference.startsWith("//")) {
                return null;
            }
            if (reference.startsWith("/")) {
                return pathPrefix + withoutEndpointCredentials(endpoint, reference);
            }
            String origin = TileUrls.origin(reference);
            if (origin != null && TileUrls.sameOrigin(endpointBase + "/", reference)) {
                return pathPrefix + TileUrls.pathAndQuery(withoutEndpointCredentials(endpoint, reference));
            }
            return null;
        }).json();
    }

    private static void respond(OutputStream out, int status, String contentType, byte[] body, boolean headOnly)
            throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        head.append("Content-Type: ").append(contentType.replace("\r", "").replace("\n", "")).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        if (!headOnly) {
            out.write(body);
        }
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 502:
                return "Bad Gateway";
            default:
                return "Status";
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    /** A parsed request line; headers are read and discarded. */
    static final class Request {
        final String method;
        final String target;
        final boolean head;

        Request(String method, String target) {
            this.method = method;
            this.target = target;
            this.head = "HEAD".equals(method);
        }

        static Request read(InputStream in) throws IOException {
            ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
            int last1 = -1;
            int last2 = -1;
            int last3 = -1;
            int b;
            // The head ends at a blank line: CRLF CRLF (or bare LF LF).
            while ((b = in.read()) != -1) {
                headBytes.write(b);
                if (headBytes.size() > MAX_REQUEST_HEAD_BYTES) {
                    return null;
                }
                boolean crlfBlankLine = b == '\n' && last1 == '\r' && last2 == '\n' && last3 == '\r';
                boolean lfBlankLine = b == '\n' && last1 == '\n';
                if (crlfBlankLine || lfBlankLine) {
                    break;
                }
                last3 = last2;
                last2 = last1;
                last1 = b;
            }
            String text = new String(headBytes.toByteArray(), StandardCharsets.ISO_8859_1);
            int lineEnd = text.indexOf('\n');
            String requestLine = (lineEnd >= 0 ? text.substring(0, lineEnd) : text).trim();
            String[] parts = requestLine.split(" ");
            if (parts.length != 3 || !parts[2].toUpperCase(Locale.ROOT).startsWith("HTTP/")) {
                return null;
            }
            return new Request(parts[0].toUpperCase(Locale.ROOT), parts[1]);
        }
    }

    static final class Response {
        final int status;
        final String contentType;
        final byte[] body;

        Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        static Response text(int status, String message) {
            return new Response(status, "text/plain; charset=utf-8", Redactor.redact(message).getBytes(StandardCharsets.UTF_8));
        }
    }
}
