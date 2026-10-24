package com.cesium.atak.ion;

import com.cesium.tak.ion.imagery.TileTemplate;

/**
 * Writes ATAK's MOBAC {@code customMapSource} XML for a raster tile source.
 * Pure Java so it can be unit tested.
 */
final class MapSourceXml {
    private MapSourceXml() {
    }

    /** {@code cesium-ion-<id>.xml}. */
    static String fileName(long assetId) {
        return "cesium-ion-" + assetId + ".xml";
    }

    /**
     * @param url the template to fetch, i.e. {@link TileTemplate#url()} or
     *     its proxy URL; must not contain credentials
     */
    static String xml(String name, TileTemplate template, String url) {
        String mobacUrl = url
                .replace("{z}", "{$z}")
                .replace("{x}", "{$x}")
                .replace("{y}", "{$y}")
                .replace("{q}", "{$q}");
        StringBuilder xml = new StringBuilder()
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<customMapSource>\n")
                .append("  <name>").append(escape(name)).append("</name>\n")
                .append("  <minZoom>").append(template.minZoom()).append("</minZoom>\n")
                .append("  <maxZoom>").append(template.maxZoom()).append("</maxZoom>\n")
                .append("  <tileType>").append(escape(template.extension())).append("</tileType>\n")
                .append("  <tileUpdate>None</tileUpdate>\n")
                .append("  <url>").append(escape(mobacUrl)).append("</url>\n");
        if (template.scheme() == TileTemplate.Scheme.TMS) {
            xml.append("  <invertYCoordinate>true</invertYCoordinate>\n");
        }
        if (template.isGeographic()) {
            xml.append("  <coordinatesystem>EPSG:4326</coordinatesystem>\n");
        }
        return xml
                .append("  <backgroundColor>#000000</backgroundColor>\n")
                .append("  <ignoreErrors>true</ignoreErrors>\n")
                .append("</customMapSource>\n")
                .toString();
    }

    /** Below this many metres per pixel at the sharpest level, imagery looks fine at street level. */
    private static final double COARSE_METRES_PER_PIXEL = 5.0;

    /**
     * A note for imagery whose sharpest level is coarse (e.g. Blue Marble,
     * about 500 m per pixel): zoomed in past it, ATAK enlarges the last
     * tiles into a blur of flat colour. Null for sharp imagery.
     */
    static String resolutionNote(String name, TileTemplate template) {
        // 256-pixel tiles: 2 * pi * 6378137 / 256 (Web Mercator) or half that (geographic) at the equator.
        double levelZero = template.isGeographic() ? 78271.517 : 156543.034;
        double metresPerPixel = levelZero / Math.pow(2, template.maxZoom());
        if (metresPerPixel < COARSE_METRES_PER_PIXEL) {
            return null;
        }
        String detail = metresPerPixel >= 1000
                ? String.format(java.util.Locale.US, "%.0f km", metresPerPixel / 1000)
                : String.format(java.util.Locale.US, "%.0f m", metresPerPixel);
        return name + " has detail down to about " + detail + " per pixel. Zoomed in closer, ATAK enlarges it"
                + " into flat colour: zoom out to see it.";
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':
                    escaped.append("&amp;");
                    break;
                case '<':
                    escaped.append("&lt;");
                    break;
                case '>':
                    escaped.append("&gt;");
                    break;
                case '"':
                    escaped.append("&quot;");
                    break;
                case '\'':
                    escaped.append("&apos;");
                    break;
                default:
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        escaped.append(c);
                    }
            }
        }
        return escaped.toString();
    }
}
