package com.cesium.tak.ion.http;

import com.cesium.tak.ion.util.Redactor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * {@link HttpTransport} on {@link HttpURLConnection}, which both the desktop
 * JDK and Android provide.
 *
 * <p>Redirects are followed manually so that credentials never leak:
 * {@code Authorization} is dropped when a redirect changes origin, and
 * HTTPS-to-HTTP redirects are refused. POSTs are never redirected (a
 * redirect is returned as the response), so a body is sent only where asked.
 */
public final class UrlConnectionTransport implements HttpTransport {
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 15_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 30_000;
    /** Larger than any ion API response or 3D Tiles content we expect. */
    public static final int DEFAULT_MAX_BODY_BYTES = 64 * 1024 * 1024;
    static final int MAX_REDIRECTS = 5;

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxBodyBytes;
    private final SSLSocketFactory sslSocketFactory;
    private final String userAgent;

    public UrlConnectionTransport() {
        this(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, DEFAULT_MAX_BODY_BYTES, null, "cesium-ion-tak");
    }

    /**
     * @param sslSocketFactory trust configuration for HTTPS, or {@code null}
     *     for the platform default (use this for a private-CA self-hosted server)
     */
    public UrlConnectionTransport(
            int connectTimeoutMs,
            int readTimeoutMs,
            int maxBodyBytes,
            SSLSocketFactory sslSocketFactory,
            String userAgent) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxBodyBytes = maxBodyBytes;
        this.sslSocketFactory = sslSocketFactory;
        this.userAgent = userAgent;
    }

    @Override
    public HttpResponse send(HttpRequest request) throws IOException {
        URI current = request.uri();
        boolean sendAuthorization = true;
        for (int redirects = 0; ; redirects++) {
            HttpURLConnection connection = open(current, request.method(), request.headers(), sendAuthorization);
            try {
                byte[] requestBody = request.body();
                if (requestBody != null) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(requestBody.length);
                    try (java.io.OutputStream output = connection.getOutputStream()) {
                        output.write(requestBody);
                    }
                }
                int status = connection.getResponseCode();
                if (isRedirect(status) && requestBody == null) {
                    if (redirects >= MAX_REDIRECTS) {
                        throw new IOException("Too many redirects from " + Redactor.redact(request.uri().toString()));
                    }
                    URI next = redirectTarget(current, connection.getHeaderField("Location"));
                    if ("https".equals(scheme(current)) && !"https".equals(scheme(next))) {
                        throw new IOException("Refused an HTTPS to HTTP redirect to " + Redactor.redact(next.toString()));
                    }
                    if (!sameOrigin(current, next)) {
                        sendAuthorization = false;
                    }
                    drain(connection);
                    current = next;
                    continue;
                }
                byte[] body = readBody(connection, status);
                return new HttpResponse(status, current, connection.getHeaderFields(), body);
            } finally {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection open(URI uri, String method, Map<String, String> headers, boolean sendAuthorization)
            throws IOException {
        String scheme = scheme(uri);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IOException("Unsupported URL scheme: " + scheme);
        }
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        if (sslSocketFactory != null && connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(sslSocketFactory);
        }
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept-Encoding", "gzip");
        if (userAgent != null) {
            connection.setRequestProperty("User-Agent", userAgent);
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!sendAuthorization && "authorization".equalsIgnoreCase(header.getKey())) {
                continue;
            }
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        return connection;
    }

    private byte[] readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream raw = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (raw == null) {
            return new byte[0];
        }
        long declaredLength = connection.getContentLengthLong();
        if (declaredLength > maxBodyBytes) {
            raw.close();
            throw new IOException("Response is larger than the " + maxBodyBytes + " byte limit.");
        }
        String encoding = connection.getContentEncoding();
        try (InputStream input = encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")
                ? new GZIPInputStream(raw)
                : raw) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    declaredLength > 0 ? (int) declaredLength : 8192);
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBodyBytes) {
                    throw new IOException("Response is larger than the " + maxBodyBytes + " byte limit.");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void drain(HttpURLConnection connection) {
        try (InputStream input = connection.getErrorStream() != null
                ? connection.getErrorStream()
                : connection.getInputStream()) {
            if (input != null) {
                byte[] buffer = new byte[4096];
                while (input.read(buffer) != -1) {
                    // Discard redirect bodies so the connection can be reused.
                }
            }
        } catch (IOException ignored) {
            // A redirect body that cannot be read does not affect the result.
        }
    }

    static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    static URI redirectTarget(URI current, String location) throws IOException {
        if (location == null || location.trim().isEmpty()) {
            throw new IOException("Redirect without a Location header.");
        }
        try {
            return current.resolve(new URI(location.trim()));
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            throw new IOException("Redirect to an invalid location.", invalid);
        }
    }

    static boolean sameOrigin(URI left, URI right) {
        return scheme(left).equals(scheme(right))
                && left.getHost() != null
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static String scheme(URI uri) {
        return uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equals(scheme(uri)) ? 443 : 80;
    }
}
