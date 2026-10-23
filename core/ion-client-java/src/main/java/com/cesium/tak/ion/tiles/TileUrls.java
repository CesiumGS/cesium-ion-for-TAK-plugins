package com.cesium.tak.ion.tiles;

import com.cesium.tak.ion.util.Redactor;
import com.cesium.tak.ion.util.Secret;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * String-based URL operations for streaming 3D Tiles, terrain, and imagery.
 *
 * <p>These work on strings rather than {@link java.net.URI} because tileset
 * references may contain implicit-tiling templates such as
 * {@code {level}/{x}/{y}.glb}, which {@code java.net.URI} rejects, and because
 * {@code java.net.URI#resolve} follows the obsolete RFC 2396 rules.
 */
public final class TileUrls {
    /** RFC 3986 Appendix B. */
    private static final Pattern REFERENCE = Pattern.compile(
            "^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?$", Pattern.DOTALL);

    private TileUrls() {
    }

    /** Resolves {@code reference} against the absolute {@code base} per RFC 3986 section 5.2. */
    public static String resolve(String base, String reference) {
        Parts b = Parts.parse(base);
        if (b.scheme == null) {
            throw new IllegalArgumentException("The base URL must be absolute: " + Redactor.redact(base));
        }
        Parts r = Parts.parse(reference);
        Parts t = new Parts();
        if (r.scheme != null) {
            t.scheme = r.scheme;
            t.authority = r.authority;
            t.path = removeDotSegments(r.path);
            t.query = r.query;
        } else {
            if (r.authority != null) {
                t.authority = r.authority;
                t.path = removeDotSegments(r.path);
                t.query = r.query;
            } else {
                if (r.path.isEmpty()) {
                    t.path = b.path;
                    t.query = r.query != null ? r.query : b.query;
                } else {
                    if (r.path.startsWith("/")) {
                        t.path = removeDotSegments(r.path);
                    } else {
                        t.path = removeDotSegments(merge(b, r.path));
                    }
                    t.query = r.query;
                }
                t.authority = b.authority;
            }
            t.scheme = b.scheme;
        }
        t.fragment = r.fragment;
        return t.toString();
    }

    /**
     * Resolves a tileset reference the way CesiumJS and cesium-native do:
     * the child inherits each parent query parameter it does not set itself.
     *
     * <p>This is how Google Photorealistic 3D Tiles keep the {@code key} from
     * the root URL and the {@code session} from the first child on every
     * request. Parameters are inherited only when the child stays on the
     * parent's origin, so credentials are never sent to another host.
     * Fragments are dropped.
     */
    public static String deriveChild(String parent, String reference) {
        String resolved = withoutFragment(resolve(parent, reference));
        if (!sameOrigin(parent, resolved)) {
            return resolved;
        }
        List<String[]> parentParameters = queryParameters(parent);
        if (parentParameters.isEmpty()) {
            return resolved;
        }
        Set<String> childNames = new LinkedHashSet<>();
        for (String[] parameter : queryParameters(resolved)) {
            childNames.add(parameter[0]);
        }
        StringBuilder appended = new StringBuilder();
        for (String[] parameter : parentParameters) {
            if (!childNames.contains(parameter[0])) {
                appendRawParameter(appended, parameter[2]);
            }
        }
        if (appended.length() == 0) {
            return resolved;
        }
        Parts parts = Parts.parse(resolved);
        parts.query = parts.query == null || parts.query.isEmpty()
                ? appended.toString()
                : parts.query + "&" + appended;
        return parts.toString();
    }

    /** Whether two absolute URLs share scheme, host, and effective port. */
    public static boolean sameOrigin(String left, String right) {
        String leftOrigin = origin(left);
        return leftOrigin != null && leftOrigin.equals(origin(right));
    }

    /**
     * The normalized origin ({@code scheme://host:port}, lower case, default
     * port made explicit), or {@code null} for a relative URL.
     */
    public static String origin(String url) {
        Parts parts = Parts.parse(url);
        if (parts.scheme == null || parts.authority == null) {
            return null;
        }
        String scheme = parts.scheme.toLowerCase(Locale.ROOT);
        String authority = parts.authority;
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        String host = authority;
        String port = null;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0 && authority.indexOf(']', colon) < 0) {
            host = authority.substring(0, colon);
            port = authority.substring(colon + 1);
        }
        if (port == null || port.isEmpty()) {
            port = "https".equals(scheme) ? "443" : "http".equals(scheme) ? "80" : "";
        }
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    /**
     * {@code scheme://authority} exactly as written (no default port added),
     * or {@code null} for a relative URL. Prefix it to an absolute path.
     */
    public static String originBase(String url) {
        Parts parts = Parts.parse(url);
        if (parts.scheme == null || parts.authority == null) {
            return null;
        }
        return parts.scheme + "://" + parts.authority;
    }

    /** The path plus {@code ?query} when present, without fragment. */
    public static String pathAndQuery(String url) {
        Parts parts = Parts.parse(url);
        return parts.path + (parts.query != null ? "?" + parts.query : "");
    }

    /**
     * Query parameters in order as {@code [decodedName, decodedValue, rawPair]}.
     */
    public static List<String[]> queryParameters(String url) {
        Parts parts = Parts.parse(url);
        if (parts.query == null || parts.query.isEmpty()) {
            return Collections.emptyList();
        }
        List<String[]> result = new ArrayList<>();
        for (String pair : parts.query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String rawName = equals >= 0 ? pair.substring(0, equals) : pair;
            String rawValue = equals >= 0 ? pair.substring(equals + 1) : "";
            result.add(new String[] {decode(rawName), decode(rawValue), pair});
        }
        return result;
    }

    /** The decoded value of the first parameter called {@code name}, or {@code null}. */
    public static String queryParameter(String url, String name) {
        for (String[] parameter : queryParameters(url)) {
            if (parameter[0].equals(name)) {
                return parameter[1];
            }
        }
        return null;
    }

    /**
     * Splits credentials (see {@link Redactor#SENSITIVE_QUERY_PARAMETERS}) out
     * of {@code url}, so the URL can be stored, logged, or handed to a
     * renderer, and the credentials re-applied only on the wire.
     */
    public static CredentialSplit splitCredentials(String url) {
        Map<String, Secret> credentials = new LinkedHashMap<>();
        StringBuilder kept = new StringBuilder();
        for (String[] parameter : queryParameters(url)) {
            if (Redactor.isSensitiveParameter(parameter[0]) && !parameter[1].isEmpty()) {
                credentials.put(parameter[0], Secret.of(parameter[1]));
            } else {
                appendRawParameter(kept, parameter[2]);
            }
        }
        Parts parts = Parts.parse(url);
        parts.query = kept.length() == 0 ? null : kept.toString();
        return new CredentialSplit(parts.toString(), credentials);
    }

    /** {@code url} without the query parameters whose decoded names are in {@code names}. */
    public static String withoutQueryParameters(String url, Set<String> names) {
        if (names.isEmpty()) {
            return url;
        }
        StringBuilder kept = new StringBuilder();
        for (String[] parameter : queryParameters(url)) {
            if (!names.contains(parameter[0])) {
                appendRawParameter(kept, parameter[2]);
            }
        }
        Parts parts = Parts.parse(url);
        parts.query = kept.length() == 0 ? null : kept.toString();
        return parts.toString();
    }

    /**
     * Adds each parameter in {@code parameters} that {@code url} does not
     * already set. Values are percent-encoded.
     */
    public static String withQueryParameters(String url, Map<String, Secret> parameters) {
        if (parameters.isEmpty()) {
            return url;
        }
        Set<String> existing = new LinkedHashSet<>();
        for (String[] parameter : queryParameters(url)) {
            existing.add(parameter[0]);
        }
        StringBuilder appended = new StringBuilder();
        for (Map.Entry<String, Secret> parameter : parameters.entrySet()) {
            if (!existing.contains(parameter.getKey())) {
                appendRawParameter(appended, encode(parameter.getKey()) + "=" + encode(parameter.getValue().reveal()));
            }
        }
        if (appended.length() == 0) {
            return url;
        }
        Parts parts = Parts.parse(url);
        parts.query = parts.query == null || parts.query.isEmpty()
                ? appended.toString()
                : parts.query + "&" + appended;
        return parts.toString();
    }

    public static String withoutFragment(String url) {
        int hash = url.indexOf('#');
        return hash >= 0 ? url.substring(0, hash) : url;
    }

    /** The path component, without query or fragment. */
    public static String path(String url) {
        return Parts.parse(url).path;
    }

    /** The URL with its query and fragment removed. */
    public static String withoutQuery(String url) {
        Parts parts = Parts.parse(url);
        parts.query = null;
        parts.fragment = null;
        return parts.toString();
    }

    private static void appendRawParameter(StringBuilder query, String rawPair) {
        if (query.length() > 0) {
            query.append('&');
        }
        query.append(rawPair);
    }

    private static String merge(Parts base, String referencePath) {
        if (base.authority != null && base.path.isEmpty()) {
            return "/" + referencePath;
        }
        int slash = base.path.lastIndexOf('/');
        return slash >= 0 ? base.path.substring(0, slash + 1) + referencePath : referencePath;
    }

    /** RFC 3986 section 5.2.4. */
    static String removeDotSegments(String path) {
        StringBuilder input = new StringBuilder(path);
        StringBuilder output = new StringBuilder();
        while (input.length() > 0) {
            if (startsWith(input, "../")) {
                input.delete(0, 3);
            } else if (startsWith(input, "./")) {
                input.delete(0, 2);
            } else if (startsWith(input, "/./")) {
                input.delete(0, 2);
            } else if (equals(input, "/.")) {
                input.replace(0, 2, "/");
            } else if (startsWith(input, "/../")) {
                input.delete(0, 3);
                removeLastSegment(output);
            } else if (equals(input, "/..")) {
                input.replace(0, 3, "/");
                removeLastSegment(output);
            } else if (equals(input, ".") || equals(input, "..")) {
                input.setLength(0);
            } else {
                int start = input.charAt(0) == '/' ? 1 : 0;
                int next = input.indexOf("/", start);
                int end = next >= 0 ? next : input.length();
                output.append(input, 0, end);
                input.delete(0, end);
            }
        }
        return output.toString();
    }

    private static void removeLastSegment(StringBuilder output) {
        int slash = output.lastIndexOf("/");
        output.setLength(Math.max(slash, 0));
    }

    private static boolean startsWith(StringBuilder builder, String prefix) {
        return builder.length() >= prefix.length() && builder.substring(0, prefix.length()).equals(prefix);
    }

    private static boolean equals(StringBuilder builder, String value) {
        return builder.length() == value.length() && builder.toString().equals(value);
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException malformed) {
            return raw;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A URL with its credentials removed, and the credentials themselves. */
    public static final class CredentialSplit {
        private final String url;
        private final Map<String, Secret> credentials;

        CredentialSplit(String url, Map<String, Secret> credentials) {
            this.url = url;
            this.credentials = Collections.unmodifiableMap(credentials);
        }

        public String url() {
            return url;
        }

        public Map<String, Secret> credentials() {
            return credentials;
        }
    }

    private static final class Parts {
        String scheme;
        String authority;
        String path = "";
        String query;
        String fragment;

        static Parts parse(String reference) {
            Matcher matcher = REFERENCE.matcher(reference == null ? "" : reference);
            Parts parts = new Parts();
            if (!matcher.matches()) {
                parts.path = reference == null ? "" : reference;
                return parts;
            }
            parts.scheme = matcher.group(2);
            parts.authority = matcher.group(3) != null ? matcher.group(4) : null;
            parts.path = matcher.group(5) == null ? "" : matcher.group(5);
            parts.query = matcher.group(6) != null ? matcher.group(7) : null;
            parts.fragment = matcher.group(8) != null ? matcher.group(9) : null;
            return parts;
        }

        /** RFC 3986 section 5.3. */
        @Override
        public String toString() {
            StringBuilder result = new StringBuilder();
            if (scheme != null) {
                result.append(scheme).append(':');
            }
            if (authority != null) {
                result.append("//").append(authority);
            }
            result.append(path);
            if (query != null) {
                result.append('?').append(query);
            }
            if (fragment != null) {
                result.append('#').append(fragment);
            }
            return result.toString();
        }
    }
}
