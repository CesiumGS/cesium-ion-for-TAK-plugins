package com.cesium.tak.ion.util;

/**
 * A credential (ion access token, endpoint token, or provider key) that never
 * appears in {@link #toString()}, logs, or exception messages.
 *
 * <p>Call {@link #reveal()} only at the point the value goes onto the wire.
 */
public final class Secret {
    private final String value;

    private Secret(String value) {
        this.value = value;
    }

    /**
     * @throws IllegalArgumentException when the value is blank or contains
     *     whitespace or control characters (which would allow HTTP header
     *     injection and are never valid in ion tokens)
     */
    public static Secret of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("A credential is required.");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A credential is required.");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c <= 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("The credential contains whitespace or control characters.");
            }
        }
        return new Secret(trimmed);
    }

    /** {@code null} for a missing or blank value; otherwise as {@link #of(String)}. */
    public static Secret ofNullable(String value) {
        return value == null || value.trim().isEmpty() ? null : of(value);
    }

    public String reveal() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Secret && ((Secret) other).value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "Secret[redacted]";
    }
}
