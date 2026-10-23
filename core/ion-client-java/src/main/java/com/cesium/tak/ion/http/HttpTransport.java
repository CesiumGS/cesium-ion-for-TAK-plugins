package com.cesium.tak.ion.http;

import java.io.IOException;

/**
 * The only way the core touches the network. Each platform can supply its
 * own, for example to use ATAK's certificate store for a self-hosted ion
 * server with a private CA.
 */
public interface HttpTransport {
    /**
     * Sends {@code request} and buffers the response, following redirects.
     * Non-2xx responses are returned, not thrown.
     *
     * @throws IOException on connection, TLS, timeout, or size-limit failures
     */
    HttpResponse send(HttpRequest request) throws IOException;
}
