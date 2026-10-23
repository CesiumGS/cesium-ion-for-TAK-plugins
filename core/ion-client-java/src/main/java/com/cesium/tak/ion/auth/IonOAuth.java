package com.cesium.tak.ion.auth;

import com.cesium.tak.ion.IonException;
import com.cesium.tak.ion.IonServer;
import com.cesium.tak.ion.http.HttpRequest;
import com.cesium.tak.ion.http.HttpResponse;
import com.cesium.tak.ion.http.HttpTransport;
import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonException;
import com.cesium.tak.ion.json.JsonObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Sign in with Cesium ion": the OAuth 2.0 authorization code flow with PKCE
 * (RFC 7636) for a public client, as Cesium for Unreal and CesiumJS-based
 * apps use. The user signs in on ion's own page in a browser; ion redirects
 * to a loopback address on the device ({@link LoopbackReceiver}) with a
 * one-time code, which is exchanged for an access token. The app never sees
 * the user's password, and no client secret is involved.
 */
public final class IonOAuth {
    /**
     * The scopes the plugins need: list the account's assets and stream them, and
     * read the account's username (shown as who is signed in). All read-only.
     */
    public static final String SCOPES = "assets:list assets:read profile:read";
    /** Fixed so the redirect URI registered with the ion OAuth application matches exactly. */
    public static final int CALLBACK_PORT = 38736;
    public static final String CALLBACK_PATH = "/cesium-tak/oauth2/callback";
    public static final String REDIRECT_URI = "http://127.0.0.1:" + CALLBACK_PORT + CALLBACK_PATH;

    private IonOAuth() {
    }

    /** ion's sign-in page: {@code <server>/oauth} (ion.cesium.com for Cloud). */
    public static URI authorizeEndpoint(IonServer server) {
        return server.serverUrl().resolve("oauth");
    }

    /** The token endpoint: {@code <api>/oauth/token}. */
    public static URI tokenEndpoint(URI apiUrl) {
        return apiUrl.resolve("oauth/token");
    }

    /** Starts a sign-in: fresh PKCE verifier and state, and the URL to open in a browser. */
    public static Session start(String clientId, IonServer server, SecureRandom random) {
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalArgumentException("No Cesium ion OAuth client ID is configured.");
        }
        String verifier = randomToken(random, 64);
        String state = randomToken(random, 24);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", clientId.trim());
        query.put("redirect_uri", REDIRECT_URI);
        query.put("scope", SCOPES);
        query.put("state", state);
        query.put("code_challenge", challenge(verifier));
        query.put("code_challenge_method", "S256");
        URI authorize = URI.create(authorizeEndpoint(server) + "?" + encode(query));
        return new Session(clientId.trim(), verifier, state, authorize);
    }

    /** One sign-in attempt. */
    public static final class Session {
        private final String clientId;
        private final String verifier;
        private final String state;
        private final URI authorizeUrl;

        Session(String clientId, String verifier, String state, URI authorizeUrl) {
            this.clientId = clientId;
            this.verifier = verifier;
            this.state = state;
            this.authorizeUrl = authorizeUrl;
        }

        /** Open this in a browser. */
        public URI authorizeUrl() {
            return authorizeUrl;
        }

        /**
         * The authorization code from the redirect's request target
         * ({@code /cesium-tak/oauth2/callback?code=...&state=...}).
         *
         * @throws IonException when the user declined, ion reported an error,
         *     or the state does not match (a forged or stale redirect)
         */
        public String codeFrom(String requestTarget) throws IonException {
            int question = requestTarget.indexOf('?');
            String path = question < 0 ? requestTarget : requestTarget.substring(0, question);
            if (!CALLBACK_PATH.equals(path)) {
                throw new IonException(IonException.Kind.CONFIGURATION, "Unexpected sign-in redirect.");
            }
            Map<String, String> query = decode(question < 0 ? "" : requestTarget.substring(question + 1));
            if (!state.equals(query.get("state"))) {
                throw new IonException(IonException.Kind.CONFIGURATION,
                        "The sign-in response did not match this request; try again.");
            }
            String error = query.get("error");
            if (error != null) {
                String description = query.get("error_description");
                throw new IonException(IonException.Kind.UNAUTHORIZED, "access_denied".equals(error)
                        ? "Sign-in was cancelled."
                        : "Cesium ion refused the sign-in: " + (description != null ? description : error));
            }
            String code = query.get("code");
            if (code == null || code.isEmpty()) {
                throw new IonException(IonException.Kind.CONFIGURATION, "The sign-in response had no code.");
            }
            return code;
        }

        /** Exchanges {@code code} for an access token at {@code tokenEndpoint}. */
        public String exchange(HttpTransport transport, URI tokenEndpoint, String code) throws IonException {
            StringBuilder body = new StringBuilder("{");
            field(body, "grant_type", "authorization_code").append(',');
            field(body, "client_id", clientId).append(',');
            field(body, "code", code).append(',');
            field(body, "redirect_uri", REDIRECT_URI).append(',');
            field(body, "code_verifier", verifier).append('}');
            HttpResponse response;
            try {
                response = transport.send(HttpRequest.post(tokenEndpoint, "application/json",
                        body.toString().getBytes(StandardCharsets.UTF_8))
                        .header("Accept", "application/json").build());
            } catch (IOException failure) {
                throw new IonException(IonException.Kind.NETWORK,
                        "Could not reach Cesium ion to finish signing in: " + failure.getMessage(), -1, failure);
            }
            if (!response.isSuccessful()) {
                throw new IonException(IonException.Kind.UNAUTHORIZED,
                        "Cesium ion did not accept the sign-in (HTTP " + response.status() + ").",
                        response.status(), null);
            }
            try {
                JsonObject json = Json.parseObject(response.bodyText());
                String token = json.optString("access_token", "");
                if (token.isEmpty()) {
                    throw new IonException(IonException.Kind.SERVER, "Cesium ion's sign-in response had no token.");
                }
                return token;
            } catch (JsonException malformed) {
                throw new IonException(IonException.Kind.SERVER, "Cesium ion's sign-in response was not JSON.",
                        response.status(), malformed);
            }
        }
    }

    /** The S256 code challenge: BASE64URL(SHA-256(verifier)), unpadded. */
    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private static String randomToken(SecureRandom random, int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static StringBuilder field(StringBuilder out, String name, String value) {
        Json.writeString(out, name);
        out.append(':');
        Json.writeString(out, value);
        return out;
    }

    static String encode(Map<String, String> query) {
        StringBuilder out = new StringBuilder();
        try {
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (out.length() > 0) {
                    out.append('&');
                }
                out.append(URLEncoder.encode(entry.getKey(), "UTF-8").replace("+", "%20")).append('=')
                        .append(URLEncoder.encode(entry.getValue(), "UTF-8").replace("+", "%20"));
            }
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
        return out.toString();
    }

    static Map<String, String> decode(String query) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            try {
                String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), "UTF-8");
                String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
                values.putIfAbsent(key, value);
            } catch (UnsupportedEncodingException | IllegalArgumentException malformed) {
                // Skip a malformed pair; a missing code or state is reported by the caller.
            }
        }
        return values;
    }
}
