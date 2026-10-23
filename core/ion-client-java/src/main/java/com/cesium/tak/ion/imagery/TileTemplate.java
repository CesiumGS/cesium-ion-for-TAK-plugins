package com.cesium.tak.ion.imagery;

/**
 * A raster tile source as a URL template, independent of any TAK map-source
 * format. Placeholders: {@code {z}}, {@code {x}}, {@code {y}}, or {@code {q}}
 * (Bing quadkey). The URL never contains credentials; sources that need them
 * are served through {@link com.cesium.tak.ion.proxy.TileProxy}.
 */
public final class TileTemplate {
    /** Tile addressing. */
    public enum Scheme {
        /** Row 0 at the top (OSM/Google/XYZ). */
        XYZ,
        /** Row 0 at the bottom (TMS). */
        TMS,
        /** Bing quadkeys. */
        QUADKEY
    }

    private final String url;
    private final Scheme scheme;
    private final int minZoom;
    private final int maxZoom;
    private final String extension;
    private final boolean geographic;
    private final boolean needsProxy;

    public TileTemplate(String url, Scheme scheme, int minZoom, int maxZoom, String extension, boolean geographic,
            boolean needsProxy) {
        this.url = url;
        this.scheme = scheme;
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.extension = extension;
        this.geographic = geographic;
        this.needsProxy = needsProxy;
    }

    public String url() {
        return url;
    }

    public Scheme scheme() {
        return scheme;
    }

    public int minZoom() {
        return minZoom;
    }

    public int maxZoom() {
        return maxZoom;
    }

    /** E.g. {@code png} or {@code jpg}. */
    public String extension() {
        return extension;
    }

    /** EPSG:4326 tiling (two root tiles) rather than Web Mercator. */
    public boolean isGeographic() {
        return geographic;
    }

    /** Whether tile requests need credentials, so must go through the proxy. */
    public boolean needsProxy() {
        return needsProxy;
    }

    @Override
    public String toString() {
        return "TileTemplate[" + scheme + " " + url + " z" + minZoom + "-" + maxZoom + "." + extension
                + (geographic ? " geographic" : "") + (needsProxy ? " proxied" : "") + "]";
    }
}
