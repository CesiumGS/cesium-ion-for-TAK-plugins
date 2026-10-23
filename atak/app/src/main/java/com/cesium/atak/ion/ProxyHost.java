package com.cesium.atak.ion;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.cesium.tak.ion.http.UrlConnectionTransport;
import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.proxy.TileProxy;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the loopback {@link TileProxy} inside ATAK for the plugin's lifetime.
 *
 * <p>The URL secret and port are stable across restarts because ATAK
 * persists imported layers (with their proxy URLs) and reloads them at
 * startup. The secret lives in a private preferences file; it unlocks only
 * assets the user loaded, never the token itself.
 */
final class ProxyHost {
    static final int PREFERRED_PORT = 38735;
    private static final int THREADS = 6;
    private static final String PREFERENCES = "cesium_ion_proxy";
    private static final String KEY_SECRET = "secret";
    private static final String KEY_PORT = "port";

    /** Requests logged in full per asset before only failures and summaries are. */
    private static final int LOGGED_REQUESTS = 3;
    private static final int LOGGED_FAILURES = 10;
    private static final int SUMMARY_EVERY = 100;
    private static final long CREDIT_LOG_MILLIS = 10_000;

    private final SharedPreferences preferences;
    private final TileProxy proxy;
    private final ConcurrentMap<Long, AtomicInteger[]> counts = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, CreditWindow> credits = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> loggedCredits = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> creditsLoggedAt = new ConcurrentHashMap<>();
    private boolean running;

    ProxyHost(Context hostContext, IonSession session) {
        preferences = hostContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        proxy = new TileProxy(session, new UrlConnectionTransport(), secret(preferences));
        proxy.setRequestListener(this::logRequest);
        proxy.setTileListener(this::recordCredits);
    }

    /**
     * Collects each tileset's per-tile copyrights (Google Photorealistic 3D
     * Tiles must show them) and logs the credit line when it changes, at
     * most every {@link #CREDIT_LOG_MILLIS}.
     */
    private void recordCredits(long assetId, byte[] content) {
        CreditWindow window = credits.computeIfAbsent(assetId, id -> new CreditWindow(System::currentTimeMillis));
        window.recordTile(content);
        String line = window.line();
        long now = System.currentTimeMillis();
        Long last = creditsLoggedAt.get(assetId);
        if (!line.isEmpty() && !line.equals(loggedCredits.get(assetId))
                && (last == null || now - last >= CREDIT_LOG_MILLIS)) {
            loggedCredits.put(assetId, line);
            creditsLoggedAt.put(assetId, now);
            PluginLog.i("Credits #" + assetId + ": " + line);
        }
    }

    /** @return the credit line for the tiles recently streamed for {@code assetId}, or "" */
    String creditLine(long assetId) {
        CreditWindow window = credits.get(assetId);
        return window == null ? "" : window.line();
    }

    /**
     * Logs the first requests for each asset, early failures, and a periodic
     * summary: enough to diagnose a layer without flooding logcat. Paths
     * never include the query, which may hold credentials.
     */
    private void logRequest(long assetId, String path, int status, int bytes, long millis, String detail) {
        AtomicInteger[] count = counts.computeIfAbsent(assetId,
                id -> new AtomicInteger[] {new AtomicInteger(), new AtomicInteger()});
        int requests = count[0].incrementAndGet();
        boolean failed = status >= 400;
        int failures = failed ? count[1].incrementAndGet() : count[1].get();
        if (requests <= LOGGED_REQUESTS || (failed && failures <= LOGGED_FAILURES)) {
            PluginLog.i(String.format(Locale.US, "Proxy #%d %s -> %d, %d bytes, %d ms%s", assetId, path, status,
                    bytes, millis, detail == null ? "" : ": " + detail));
        }
        if (requests % SUMMARY_EVERY == 0) {
            PluginLog.i("Proxy #" + assetId + ": " + requests + " requests, " + failures + " failed.");
        }
    }

    /** Call off the UI thread. */
    synchronized void start() throws IOException {
        int preferred = preferences.getInt(KEY_PORT, PREFERRED_PORT);
        int port = proxy.start(preferred, THREADS);
        running = true;
        if (port != preferred) {
            PluginLog.w("Tile proxy port " + preferred + " was busy; using " + port
                    + ". Layers loaded earlier through the proxy need to be loaded again.");
            preferences.edit().putInt(KEY_PORT, port).apply();
        }
        PluginLog.i("Tile proxy listening on 127.0.0.1:" + port);
    }

    synchronized void stop() {
        running = false;
        proxy.close();
    }

    synchronized boolean isRunning() {
        return running;
    }

    void register(long assetId) {
        proxy.register(assetId);
    }

    void unregister(long assetId) {
        proxy.unregister(assetId);
    }

    String rootUrl(AssetEndpoint endpoint) {
        return proxy.rootUrl(endpoint);
    }

    String proxyUrl(AssetEndpoint endpoint, String upstreamUrl) {
        return proxy.proxyUrl(endpoint, upstreamUrl);
    }

    private static String secret(SharedPreferences preferences) {
        String secret = preferences.getString(KEY_SECRET, null);
        if (secret == null) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            secret = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            preferences.edit().putString(KEY_SECRET, secret).apply();
        }
        return secret;
    }
}
