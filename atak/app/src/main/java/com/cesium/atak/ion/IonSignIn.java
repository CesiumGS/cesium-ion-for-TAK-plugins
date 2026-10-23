package com.cesium.atak.ion;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.cesium.tak.ion.ApiUrlResolver;
import com.cesium.tak.ion.IonException;
import com.cesium.tak.ion.IonServer;
import com.cesium.tak.ion.auth.IonOAuth;
import com.cesium.tak.ion.auth.LoopbackReceiver;
import com.cesium.tak.ion.http.HttpTransport;

import java.io.IOException;
import java.net.BindException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.function.Consumer;

/**
 * "Sign in with Cesium ion" ({@link IonOAuth}): opens ion's sign-in page in
 * the device's browser, waits for the redirect to {@code 127.0.0.1}, and
 * stores the resulting access token in {@link SecureTokenStore}, exactly
 * where a pasted token would go. Neither the code nor the token is logged.
 */
final class IonSignIn {
    private static final int TIMEOUT_MS = 5 * 60_000;
    private static final String PAGE = "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>Signed in</title><style>body{margin:0;min-height:100vh;display:flex;align-items:center;"
            + "justify-content:center;background:#111827;color:#f9fafb;font:16px/1.5 sans-serif;text-align:center}"
            + "h1{font-size:22px;margin:0 0 8px}p{color:#9ca3af;margin:0}</style></head><body><div>"
            + "<h1>Signed in to Cesium ion</h1><p>You can close this tab and return to your TAK app.</p>"
            + "</div></body></html>";

    private final Context hostContext;
    private final HttpTransport transport;
    private final SecureTokenStore tokens;
    private volatile LoopbackReceiver receiver;
    private volatile boolean running;

    IonSignIn(Context hostContext, HttpTransport transport, SecureTokenStore tokens) {
        this.hostContext = hostContext;
        this.transport = transport;
        this.tokens = tokens;
    }

    boolean isRunning() {
        return running;
    }

    /** Whether this build has an ion OAuth client ID to sign in with. */
    static boolean isAvailable() {
        return !BuildConfig.ION_OAUTH_CLIENT_ID.trim().isEmpty();
    }

    /**
     * Starts signing in to the server in {@code settings}; {@code log}
     * receives progress, {@code finished} runs when it ends either way.
     */
    void start(IonSettings settings, Consumer<String> log, Runnable finished) {
        if (running) {
            log.accept("Sign-in is already open in the browser.");
            return;
        }
        running = true;
        Thread thread = new Thread(() -> run(settings, log, finished), "cesium-ion-sign-in");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops waiting for the browser. */
    void cancel() {
        LoopbackReceiver current = receiver;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    private void run(IonSettings settings, Consumer<String> log, Runnable finished) {
        try {
            IonServer server = settings.server();
            URI api = ApiUrlResolver.resolve(server, transport);
            IonOAuth.Session session = IonOAuth.start(BuildConfig.ION_OAUTH_CLIENT_ID, server, new SecureRandom());
            try (LoopbackReceiver waiting = new LoopbackReceiver(IonOAuth.CALLBACK_PORT)) {
                receiver = waiting;
                openBrowser(session.authorizeUrl());
                log.accept("Sign in to Cesium ion in the browser; you will come back here afterwards.");
                String target = waiting.await(IonOAuth.CALLBACK_PATH, PAGE, TIMEOUT_MS);
                if (target == null) {
                    log.accept("Sign-in was not completed (timed out or cancelled).");
                    return;
                }
                String code = session.codeFrom(target);
                String token = session.exchange(transport, IonOAuth.tokenEndpoint(api), code);
                tokens.save(settings.tokenSlot(), token);
                log.accept("Signed in to Cesium ion (" + settings.describe() + ").");
                bringAppToFront();
            }
        } catch (IonException failure) {
            log.accept(failure.getMessage());
        } catch (BindException portInUse) {
            log.accept("Another sign-in is still waiting; finish or close it, then try again.");
        } catch (IOException failure) {
            log.accept("Could not start signing in: " + failure.getMessage());
        } catch (GeneralSecurityException failure) {
            PluginLog.e("Could not encrypt the Cesium ion token", failure);
            log.accept("Signed in, but the token could not be stored securely on this device.");
        } catch (IllegalArgumentException | IllegalStateException failure) {
            log.accept("Sign-in failed: " + failure.getMessage());
        } catch (RuntimeException | LinkageError failure) {
            PluginLog.e("Sign-in failed", failure);
            log.accept("Sign-in failed: " + failure);
        } finally {
            receiver = null;
            running = false;
            finished.run();
        }
    }

    private void openBrowser(URI url) {
        Intent browse = new Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()));
        browse.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        browse.addCategory(Intent.CATEGORY_BROWSABLE);
        hostContext.startActivity(browse);
    }

    /** Returns to the TAK app after the browser shows its "signed in" page. */
    private void bringAppToFront() {
        Intent launch = hostContext.getPackageManager().getLaunchIntentForPackage(hostContext.getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            hostContext.startActivity(launch);
        }
    }
}
