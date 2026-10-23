package com.cesium.tak.ion;

import com.cesium.tak.ion.util.Redactor;

/**
 * A failed ion operation. Messages are redacted and suitable for display;
 * platforms can map {@link #kind()} to localized text instead.
 */
public final class IonException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** Invalid server URL, token, or settings; fix in preferences. */
        CONFIGURATION,
        /** HTTP 401: the token is wrong, expired, or revoked. */
        UNAUTHORIZED,
        /** HTTP 403: the token lacks a scope, or the asset is not shared with it. */
        FORBIDDEN,
        /** HTTP 404: no such asset, or it is not in the account's assets. */
        NOT_FOUND,
        /** HTTP 429. */
        RATE_LIMITED,
        /** Any other non-2xx response. */
        SERVER,
        /** Connection, DNS, TLS, or timeout failure. */
        NETWORK,
        /** A 2xx response that is not the expected JSON shape. */
        INVALID_RESPONSE
    }

    private final Kind kind;
    private final int httpStatus;

    public IonException(Kind kind, String message) {
        this(kind, message, -1, null);
    }

    public IonException(Kind kind, String message, int httpStatus, Throwable cause) {
        super(Redactor.redact(message), cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public Kind kind() {
        return kind;
    }

    /** The HTTP status, or {@code -1} when the failure was not an HTTP response. */
    public int httpStatus() {
        return httpStatus;
    }
}
