package com.cesium.tak.ion.util;

import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonException;
import com.cesium.tak.ion.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Reads claims from a JWT <em>without verifying it</em>.
 *
 * <p>Used only to schedule refreshes of ion endpoint tokens before they
 * expire. Never use the result to make trust decisions.
 */
public final class Jwt {
    private Jwt() {
    }

    /**
     * The {@code exp} claim in epoch seconds, or {@code -1} when the token is
     * not a JWT or has no numeric expiry.
     */
    public static long expiryEpochSeconds(String token) {
        if (token == null) {
            return -1;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return -1;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(stripPadding(parts[1]));
            JsonObject claims = Json.parseObject(new String(payload, StandardCharsets.UTF_8));
            return claims.optLong("exp", -1);
        } catch (IllegalArgumentException | JsonException malformed) {
            return -1;
        }
    }

    private static String stripPadding(String segment) {
        int end = segment.length();
        while (end > 0 && segment.charAt(end - 1) == '=') {
            end--;
        }
        return segment.substring(0, end);
    }
}
