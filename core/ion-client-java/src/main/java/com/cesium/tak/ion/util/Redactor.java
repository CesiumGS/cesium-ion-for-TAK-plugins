package com.cesium.tak.ion.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials from URLs and free text before they reach logs, UI, or
 * exception messages.
 *
 * <p>Ion-hosted tiles carry {@code access_token}; Google tiles carry
 * {@code key} and {@code session}; Bing carries {@code key}.
 */
public final class Redactor {
    public static final String MASK = "REDACTED";

    /** Query parameter names (lower case) whose values are credentials. */
    public static final Set<String> SENSITIVE_QUERY_PARAMETERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "access_token",
            "token",
            "key",
            "apikey",
            "api_key",
            "session",
            "signature",
            "sig",
            "code",
            "client_secret")));

    private static final Pattern QUERY_PARAMETER = Pattern.compile("([?&;])([^=&#\\s\"'<>]+)=([^&#\\s\"'<>]*)");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+");

    private Redactor() {
    }

    /**
     * Masks sensitive query parameter values, bearer tokens, and JWTs anywhere
     * in {@code text}. Safe to call on URLs, log lines, and error bodies.
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = QUERY_PARAMETER.matcher(text);
        StringBuffer out = new StringBuffer(text.length());
        while (matcher.find()) {
            String name = matcher.group(2);
            String replacement = isSensitiveParameter(name)
                    ? matcher.group(1) + name + "=" + MASK
                    : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        String result = BEARER.matcher(out.toString()).replaceAll("$1" + MASK);
        return JWT.matcher(result).replaceAll(MASK);
    }

    public static boolean isSensitiveParameter(String name) {
        return name != null && SENSITIVE_QUERY_PARAMETERS.contains(name.toLowerCase(Locale.ROOT));
    }
}
