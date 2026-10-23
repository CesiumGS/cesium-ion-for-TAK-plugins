package com.cesium.tak.ion;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Which Cesium ion to talk to: Cesium ion Cloud, or a Self-Hosted
 * deployment identified by the URL users open in a browser.
 *
 * <p>HTTPS is required, except for loopback hosts or when
 * {@code allowCleartext} is set explicitly for an isolated lab network.
 */
public final class IonServer {
    public enum Kind {
        CLOUD,
        SELF_HOSTED
    }

    public static final URI CLOUD_SERVER_URL = URI.create("https://ion.cesium.com/");
    public static final URI CLOUD_API_URL = URI.create("https://api.cesium.com/");

    private static final IonServer CLOUD = new IonServer(Kind.CLOUD, CLOUD_SERVER_URL, null, false);

    private final Kind kind;
    private final URI serverUrl;
    private final URI apiUrlOverride;
    private final boolean allowCleartext;

    private IonServer(Kind kind, URI serverUrl, URI apiUrlOverride, boolean allowCleartext) {
        this.kind = kind;
        this.serverUrl = serverUrl;
        this.apiUrlOverride = apiUrlOverride;
        this.allowCleartext = allowCleartext;
    }

    public static IonServer cloud() {
        return CLOUD;
    }

    /**
     * @param serverUrl the self-hosted ion URL, e.g. {@code https://ion.example.mil}
     * @param apiUrlOverride the REST API base, or {@code null}/blank to discover
     *     it from {@code <serverUrl>/config.json}
     * @param allowCleartext permit {@code http://} for non-loopback hosts
     * @throws IonException of kind {@link IonException.Kind#CONFIGURATION}
     */
    public static IonServer selfHosted(String serverUrl, String apiUrlOverride, boolean allowCleartext)
            throws IonException {
        URI server = normalize(serverUrl, "server URL", allowCleartext);
        URI api = apiUrlOverride == null || apiUrlOverride.trim().isEmpty()
                ? null
                : normalize(apiUrlOverride, "API URL", allowCleartext);
        return new IonServer(Kind.SELF_HOSTED, server, api, allowCleartext);
    }

    public Kind kind() {
        return kind;
    }

    /** Always ends with {@code /}. */
    public URI serverUrl() {
        return serverUrl;
    }

    /** The configured API base (ends with {@code /}), or {@code null}. */
    public URI apiUrlOverride() {
        return apiUrlOverride;
    }

    public boolean allowsCleartext() {
        return allowCleartext;
    }

    /**
     * Parses and checks a server or API URL: http(s) only, a host, no
     * credentials, query, or fragment; the path is given a trailing slash.
     */
    static URI normalize(String raw, String label, boolean allowCleartext) throws IonException {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IonException(IonException.Kind.CONFIGURATION, "The Cesium ion " + label + " is required.");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException invalid) {
            throw new IonException(IonException.Kind.CONFIGURATION,
                    "The Cesium ion " + label + " is not a valid URL.", -1, invalid);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IonException(IonException.Kind.CONFIGURATION,
                    "The Cesium ion " + label + " must start with https://.");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IonException(IonException.Kind.CONFIGURATION, "The Cesium ion " + label + " needs a host name.");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IonException(IonException.Kind.CONFIGURATION,
                    "The Cesium ion " + label + " cannot include credentials, a query, or a fragment.");
        }
        if (scheme.equals("http") && !allowCleartext && !isLoopback(uri.getHost())) {
            throw new IonException(IonException.Kind.CONFIGURATION,
                    "The Cesium ion " + label + " must use https:// unless cleartext HTTP is explicitly allowed.");
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        try {
            return new URI(scheme + "://" + uri.getRawAuthority() + path);
        } catch (URISyntaxException invalid) {
            throw new IonException(IonException.Kind.CONFIGURATION,
                    "The Cesium ion " + label + " is not a valid URL.", -1, invalid);
        }
    }

    static boolean isLoopback(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost")
                || normalized.startsWith("127.")
                || normalized.equals("[::1]")
                || normalized.equals("::1");
    }

    @Override
    public String toString() {
        return "IonServer[" + kind + ", " + serverUrl + (apiUrlOverride != null ? ", api=" + apiUrlOverride : "") + "]";
    }
}
