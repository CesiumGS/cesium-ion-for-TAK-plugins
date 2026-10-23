package com.cesium.tak.ion.auth;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Waits for the browser's sign-in redirect on {@code 127.0.0.1} (RFC 8252,
 * loopback interface redirection). Only connections from this device can
 * reach it; requests to other paths (a browser's favicon fetch) get a 404 and
 * are ignored. One redirect is answered with a short page telling the user to
 * return to the app; then the socket closes.
 */
public final class LoopbackReceiver implements Closeable {
    private static final int MAX_REQUEST_LINE = 8192;

    private final ServerSocket socket;

    /** Binds to {@code 127.0.0.1:port} ({@code 0} picks a free port). */
    public LoopbackReceiver(int port) throws IOException {
        socket = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
    }

    public int port() {
        return socket.getLocalPort();
    }

    /**
     * Waits up to {@code timeoutMillis} for a request to {@code path}, answers
     * it with {@code pageHtml}, and returns its request target (path and
     * query), or {@code null} when the time runs out or {@link #close} is called.
     */
    public String await(String path, String pageHtml, int timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0 || socket.isClosed()) {
                return null;
            }
            socket.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
            try (Socket client = socket.accept()) {
                client.setSoTimeout(5000);
                String target = requestTarget(client);
                boolean ours = target != null && (target.equals(path) || target.startsWith(path + "?"));
                respond(client, ours ? 200 : 404, ours ? pageHtml : "Not found");
                if (ours) {
                    return target;
                }
            } catch (SocketTimeoutException timedOut) {
                return null;
            } catch (IOException closedOrBroken) {
                if (socket.isClosed()) {
                    return null;
                }
                // A client that disconnected early; keep waiting for the redirect.
            }
        }
    }

    private static String requestTarget(Socket client) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
        String line = reader.readLine();
        if (line == null || line.length() > MAX_REQUEST_LINE) {
            return null;
        }
        String[] parts = line.split(" ");
        if (parts.length < 3 || !"GET".equals(parts[0])) {
            return null;
        }
        // Read (and discard) the headers so the browser sees a clean exchange.
        for (int headers = 0; headers < 100; headers++) {
            String header = reader.readLine();
            if (header == null || header.isEmpty()) {
                break;
            }
        }
        return parts[1];
    }

    private static void respond(Socket client, int status, String body) throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Not Found") + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + content.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream output = client.getOutputStream();
        output.write(head.getBytes(StandardCharsets.ISO_8859_1));
        output.write(content);
        output.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
