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
