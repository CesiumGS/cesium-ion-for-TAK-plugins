package com.cesium.tak.ion.json;

/** Malformed JSON, or JSON whose shape does not match what the caller needs. */
public final class JsonException extends Exception {
    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }
}
