package com.cesium.tak.ion.vector;

import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonException;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts GeoJSON (RFC 7946) to KML whose geometry floats a few metres
 * above the ground ({@code relativeToGround}) instead of being draped on it.
 *
 * <p>TAK drapes GeoJSON on its terrain surface, which photorealistic 3D
 * Tiles cover and hide; geometry at a relative altitude is drawn as 3D and
 * stays visible over the mesh. Styles follow the simplestyle convention
 * ({@code stroke}, {@code stroke-width}, {@code stroke-opacity}, {@code fill},
 * {@code fill-opacity}); properties become ExtendedData.
 */
public final class GeoJsonToKml {
    /** ATAK's default overlay orange. */
    private static final String DEFAULT_COLOR = "#ff8800";
    private static final double DEFAULT_FILL_OPACITY = 0.5;
    private static final int MAX_DEPTH = 64;

    private final double altitudeMetres;
    private final StringBuilder out = new StringBuilder(4096);
    private int placemarks;

    private GeoJsonToKml(double altitudeMetres) {
        this.altitudeMetres = altitudeMetres;
    }

    /**
     * @param altitudeMetres height above the ground for every position
     * @return the KML document, or {@code null} for malformed GeoJSON or
     *     GeoJSON without any geometry
     */
    public static String convert(String geoJson, String name, double altitudeMetres) {
        Object root;
        try {
            root = Json.parse(geoJson);
        } catch (JsonException malformed) {
            return null;
        }
        GeoJsonToKml converter = new GeoJsonToKml(altitudeMetres);
        converter.out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
                .append("<name>").append(escape(name)).append("</name>\n");
        converter.object(root, 0);
        converter.out.append("</Document>\n</kml>\n");
        return converter.placemarks == 0 ? null : converter.out.toString();
    }

    private void object(Object node, int depth) {
        if (!(node instanceof Map) || depth > MAX_DEPTH) {
            return;
        }
        Map<?, ?> map = (Map<?, ?>) node;
        Object type = map.get("type");
        if ("FeatureCollection".equals(type)) {
            Object features = map.get("features");
            if (features instanceof List) {
                for (Object feature : (List<?>) features) {
                    object(feature, depth + 1);
                }
            }
        } else if ("Feature".equals(type)) {
            Object properties = map.get("properties");
            placemark(map.get("geometry"), properties instanceof Map ? (Map<?, ?>) properties : null);
        } else {
            placemark(map, null);
        }
    }

    private void placemark(Object geometry, Map<?, ?> properties) {
        StringBuilder shape = new StringBuilder();
        if (!geometry(geometry, shape, 0)) {
            return;
        }
        placemarks++;
        out.append("<Placemark>\n");
        String title = title(properties);
        if (title != null) {
            out.append("<name>").append(escape(title)).append("</name>\n");
        }
        style(properties);
        if (properties != null && !properties.isEmpty()) {
            out.append("<ExtendedData>\n");
            for (Map.Entry<?, ?> property : properties.entrySet()) {
                Object value = property.getValue();
                if (value == null || value instanceof Map || value instanceof List) {
                    continue;
                }
                out.append("<Data name=\"").append(escape(String.valueOf(property.getKey()))).append("\"><value>")
                        .append(escape(String.valueOf(value))).append("</value></Data>\n");
            }
            out.append("</ExtendedData>\n");
        }
        out.append(shape).append("</Placemark>\n");
    }

    private boolean geometry(Object node, StringBuilder shape, int depth) {
        if (!(node instanceof Map) || depth > MAX_DEPTH) {
            return false;
        }
        Map<?, ?> map = (Map<?, ?>) node;
        Object type = map.get("type");
        Object coordinates = map.get("coordinates");
        if ("Point".equals(type)) {
            String position = positions(coordinates == null ? null : Collections.singletonList(coordinates));
            if (position == null) {
                return false;
            }
            shape.append("<Point>").append(altitudeMode()).append("<coordinates>").append(position)
                    .append("</coordinates></Point>\n");
            return true;
        }
        if ("LineString".equals(type)) {
            return lineString(coordinates, shape);
        }
        if ("Polygon".equals(type)) {
            return polygon(coordinates, shape);
        }
        if ("MultiPoint".equals(type) || "MultiLineString".equals(type) || "MultiPolygon".equals(type)) {
            if (!(coordinates instanceof List)) {
                return false;
            }
            String single = ((String) type).substring("Multi".length());
            StringBuilder parts = new StringBuilder();
            boolean any = false;
            for (Object part : (List<?>) coordinates) {
                Map<String, Object> member = new HashMap<>();
                member.put("type", single);
                member.put("coordinates", part);
                any |= geometry(member, parts, depth + 1);
            }
            if (any) {
                shape.append("<MultiGeometry>\n").append(parts).append("</MultiGeometry>\n");
            }
            return any;
        }
        if ("GeometryCollection".equals(type)) {
            Object geometries = map.get("geometries");
            if (!(geometries instanceof List)) {
                return false;
            }
            StringBuilder parts = new StringBuilder();
            boolean any = false;
            for (Object member : (List<?>) geometries) {
                any |= geometry(member, parts, depth + 1);
            }
            if (any) {
                shape.append("<MultiGeometry>\n").append(parts).append("</MultiGeometry>\n");
            }
            return any;
        }
        return false;
    }

    private boolean lineString(Object coordinates, StringBuilder shape) {
        String line = positions(coordinates);
        if (line == null) {
            return false;
        }
        shape.append("<LineString>").append(altitudeMode()).append("<coordinates>").append(line)
                .append("</coordinates></LineString>\n");
        return true;
    }

    private boolean polygon(Object coordinates, StringBuilder shape) {
        if (!(coordinates instanceof List) || ((List<?>) coordinates).isEmpty()) {
            return false;
        }
        List<?> rings = (List<?>) coordinates;
        String outer = positions(rings.get(0));
        if (outer == null) {
            return false;
        }
        shape.append("<Polygon>").append(altitudeMode())
                .append("<outerBoundaryIs><LinearRing><coordinates>").append(outer)
                .append("</coordinates></LinearRing></outerBoundaryIs>");
        for (int i = 1; i < rings.size(); i++) {
            String inner = positions(rings.get(i));
            if (inner != null) {
                shape.append("<innerBoundaryIs><LinearRing><coordinates>").append(inner)
                        .append("</coordinates></LinearRing></innerBoundaryIs>");
            }
        }
        shape.append("</Polygon>\n");
        return true;
    }

    /** "lon,lat,alt lon,lat,alt ..." for a list of positions, or null if none is valid. */
    private String positions(Object list) {
        if (!(list instanceof List)) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (Object position : (List<?>) list) {
            if (!(position instanceof List) || ((List<?>) position).size() < 2) {
                continue;
            }
            Object lon = ((List<?>) position).get(0);
            Object lat = ((List<?>) position).get(1);
            if (!(lon instanceof Number) || !(lat instanceof Number)) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(number(((Number) lon).doubleValue())).append(',')
                    .append(number(((Number) lat).doubleValue())).append(',').append(number(altitudeMetres));
        }
        return text.length() == 0 ? null : text.toString();
    }

    private String altitudeMode() {
        return "<altitudeMode>relativeToGround</altitudeMode>";
    }

    private void style(Map<?, ?> properties) {
        String stroke = color(properties, "stroke", DEFAULT_COLOR);
        String fill = color(properties, "fill", DEFAULT_COLOR);
        double strokeOpacity = number(properties, "stroke-opacity", 1.0);
        double fillOpacity = number(properties, "fill-opacity", DEFAULT_FILL_OPACITY);
        double width = number(properties, "stroke-width", 2.0);
        out.append("<Style><LineStyle><color>").append(kmlColor(stroke, strokeOpacity)).append("</color><width>")
                .append(number(width)).append("</width></LineStyle><PolyStyle><color>")
                .append(kmlColor(fill, fillOpacity)).append("</color></PolyStyle></Style>\n");
    }

    private static String title(Map<?, ?> properties) {
        if (properties == null) {
            return null;
        }
        for (String key : new String[] {"name", "title", "NAME", "Name", "id"}) {
            Object value = properties.get(key);
            if (value != null && !(value instanceof Map) && !(value instanceof List)) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static String color(Map<?, ?> properties, String key, String fallback) {
        Object value = properties == null ? null : properties.get(key);
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.matches("#?[0-9a-fA-F]{6}") || text.matches("#?[0-9a-fA-F]{3}")) {
                return text.startsWith("#") ? text : "#" + text;
            }
        }
        return fallback;
    }

    private static double number(Map<?, ?> properties, String key, double fallback) {
        Object value = properties == null ? null : properties.get(key);
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isNaN(number) && !Double.isInfinite(number)) {
                return number;
            }
        }
        return fallback;
    }

    /** KML colours are aabbggrr. */
    static String kmlColor(String cssHex, double opacity) {
        String hex = cssHex.substring(1);
        if (hex.length() == 3) {
            hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        }
        int alpha = (int) Math.round(Math.max(0, Math.min(1, opacity)) * 255);
        return String.format(Locale.ROOT, "%02x%s%s%s", alpha,
                hex.substring(4, 6), hex.substring(2, 4), hex.substring(0, 2)).toLowerCase(Locale.ROOT);
    }

    private static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
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
                    if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                        continue;
                    }
                    escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
