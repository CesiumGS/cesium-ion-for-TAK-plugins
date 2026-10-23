package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cesium.tak.ion.auth.IonOAuth;
import com.cesium.tak.ion.auth.LoopbackReceiver;
import com.cesium.tak.ion.http.HttpRequest;
import com.cesium.tak.ion.http.HttpResponse;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

class IonOAuthTest {
    @Test
    void authorizeUrlCarriesPkceAndTheRegisteredRedirect() {
        IonOAuth.Session session = IonOAuth.start("2438", IonServer.cloud(), new SecureRandom());
        String url = session.authorizeUrl().toString();
        assertTrue(url.startsWith("https://ion.cesium.com/oauth?"), url);
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("client_id=2438"));
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A38736%2Fcesium-tak%2Foauth2%2Fcallback"));
        assertTrue(url.contains("scope=assets%3Alist%20assets%3Aread%20profile%3Aread"));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("code_challenge="));
        assertTrue(url.contains("state="));
        assertNotEquals(url, IonOAuth.start("2438", IonServer.cloud(), new SecureRandom()).authorizeUrl().toString());
    }

    @Test
    void selfHostedSignInUsesTheServersOwnPages() throws Exception {
        IonServer server = IonServer.selfHosted("http://192.168.1.247:32470/", "http://192.168.1.247:31813/", true);
        assertTrue(IonOAuth.start("7", server, new SecureRandom()).authorizeUrl().toString()
                .startsWith("http://192.168.1.247:32470/oauth?"));
        assertEquals(URI.create("http://192.168.1.247:31813/oauth/token"),
                IonOAuth.tokenEndpoint(URI.create("http://192.168.1.247:31813/")));
    }

    @Test
    void codeIsTakenOnlyFromAMatchingRedirect() throws Exception {
        IonOAuth.Session session = IonOAuth.start("2438", IonServer.cloud(), new SecureRandom());
        String state = query(session).get("state");
        assertEquals("abc", session.codeFrom(IonOAuth.CALLBACK_PATH + "?code=abc&state=" + state));
        IonException forged = assertThrows(IonException.class,
                () -> session.codeFrom(IonOAuth.CALLBACK_PATH + "?code=abc&state=other"));
        assertTrue(forged.getMessage().contains("did not match"));
        IonException declined = assertThrows(IonException.class,
                () -> session.codeFrom(IonOAuth.CALLBACK_PATH + "?error=access_denied&state=" + state));
        assertEquals("Sign-in was cancelled.", declined.getMessage());
        assertThrows(IonException.class, () -> session.codeFrom("/elsewhere?code=abc&state=" + state));
    }

    @Test
    void exchangePostsTheVerifierAndReturnsTheToken() throws Exception {
        IonOAuth.Session session = IonOAuth.start("2438", IonServer.cloud(), new SecureRandom());
        List<HttpRequest> sent = new ArrayList<>();
        String token = session.exchange(request -> {
            sent.add(request);
            return new HttpResponse(200, request.uri(), Collections.<String, List<String>>emptyMap(),
                    "{\"access_token\":\"eyJabc\",\"token_type\":\"bearer\"}".getBytes(StandardCharsets.UTF_8));
        }, IonOAuth.tokenEndpoint(IonServer.CLOUD_API_URL), "the-code");
        assertEquals("eyJabc", token);
        HttpRequest request = sent.get(0);
        assertEquals("POST", request.method());
        assertEquals(URI.create("https://api.cesium.com/oauth/token"), request.uri());
        String body = new String(request.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"grant_type\":\"authorization_code\""), body);
        assertTrue(body.contains("\"client_id\":\"2438\""));
        assertTrue(body.contains("\"code\":\"the-code\""));
        assertTrue(body.contains("\"code_verifier\":\""));
        assertTrue(body.contains("\"redirect_uri\":\"http://127.0.0.1:38736/cesium-tak/oauth2/callback\""));
        assertTrue(!request.toString().contains("the-code"), "bodies are not printed");
    }

    @Test
    void refusedExchangesAreReportedWithoutTheResponse() {
        IonOAuth.Session session = IonOAuth.start("2438", IonServer.cloud(), new SecureRandom());
        IonException refused = assertThrows(IonException.class, () -> session.exchange(request ->
                new HttpResponse(400, request.uri(), Collections.<String, List<String>>emptyMap(),
                        "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8)),
                IonOAuth.tokenEndpoint(IonServer.CLOUD_API_URL), "x"));
        assertEquals(400, refused.httpStatus());
    }

    @Test
    void pkceChallengeMatchesRfc7636() throws Exception {
        java.lang.reflect.Method challenge = IonOAuth.class.getDeclaredMethod("challenge", String.class);
        challenge.setAccessible(true);
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                challenge.invoke(null, "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void loopbackReceiverReturnsTheRedirectAndAnswersTheBrowser() throws Exception {
        try (LoopbackReceiver receiver = new LoopbackReceiver(0)) {
            CompletableFuture<String> target = CompletableFuture.supplyAsync(() -> {
                try {
                    return receiver.await(IonOAuth.CALLBACK_PATH, "<p>Signed in</p>", 10_000);
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException(failure);
                }
            });
            assertTrue(get(receiver.port(), "/favicon.ico").startsWith("HTTP/1.1 404"));
            String answer = get(receiver.port(), IonOAuth.CALLBACK_PATH + "?code=c1&state=s1");
            assertTrue(answer.startsWith("HTTP/1.1 200"));
            assertTrue(answer.endsWith("<p>Signed in</p>"));
            assertEquals(IonOAuth.CALLBACK_PATH + "?code=c1&state=s1", target.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void loopbackReceiverTimesOut() throws Exception {
        try (LoopbackReceiver receiver = new LoopbackReceiver(0)) {
            assertNull(receiver.await(IonOAuth.CALLBACK_PATH, "x", 200));
        }
    }

    private static Map<String, String> query(IonOAuth.Session session) throws Exception {
        java.lang.reflect.Method decode = IonOAuth.class.getDeclaredMethod("decode", String.class);
        decode.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> query = (Map<String, String>) decode.invoke(null, session.authorizeUrl().getRawQuery());
        return query;
    }

    private static String get(int port, String target) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                all.write(buffer, 0, read);
            }
            return new String(all.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
