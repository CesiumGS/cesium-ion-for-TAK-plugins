package com.cesium.tak.ion.tiles;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parsed {@code tilemapresource.xml} for TMS imagery hosted by ion.
 *
 * <p>Tells a platform the tile format, zoom range, tiling scheme (Web
 * Mercator or geographic), and extent, all needed to describe the layer to a
 * TAK map source.
 */
public final class TmsMetadata {
    private final String srs;
    private final String profile;
    private final String extension;
    private final String mimeType;
    private final int tileWidth;
    private final int tileHeight;
    private final int minZoom;
    private final int maxZoom;
    private final double[] boundingBox;

    private TmsMetadata(String srs, String profile, String extension, String mimeType, int tileWidth, int tileHeight,
            int minZoom, int maxZoom, double[] boundingBox) {
        this.srs = srs;
        this.profile = profile;
        this.extension = extension;
        this.mimeType = mimeType;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.boundingBox = boundingBox;
    }

    /**
     * Parses a TMS document. Documents containing a DOCTYPE are rejected
     * outright, which rules out XXE and entity-expansion attacks on every
     * platform's XML parser.
     *
     * @throws IllegalArgumentException when the document is not usable
     */
    public static TmsMetadata parse(byte[] xml) {
        String text = new String(xml, StandardCharsets.UTF_8);
        if (text.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")) {
            throw new IllegalArgumentException("TMS metadata with a DOCTYPE is not accepted.");
        }
        Document document;
        try {
            DocumentBuilder builder = hardenedFactory().newDocumentBuilder();
            document = builder.parse(new ByteArrayInputStream(xml));
        } catch (Exception malformed) {
            throw new IllegalArgumentException("TMS metadata is not valid XML.", malformed);
        }

        Element root = document.getDocumentElement();
        if (root == null || !"TileMap".equals(localName(root))) {
            throw new IllegalArgumentException("TMS metadata has no TileMap element.");
        }

        String srs = text(root, "SRS");
        Element tileFormat = first(root, "TileFormat");
        String extension = tileFormat == null ? "png" : attribute(tileFormat, "extension", "png");
        String mimeType = tileFormat == null ? "" : attribute(tileFormat, "mime-type", "");
        int tileWidth = tileFormat == null ? 256 : parseInt(attribute(tileFormat, "width", "256"), 256);
        int tileHeight = tileFormat == null ? 256 : parseInt(attribute(tileFormat, "height", "256"), 256);

        Element tileSets = first(root, "TileSets");
        String profile = tileSets == null ? "" : attribute(tileSets, "profile", "");
        int minZoom = Integer.MAX_VALUE;
        int maxZoom = Integer.MIN_VALUE;
        NodeList sets = root.getElementsByTagName("TileSet");
        for (int i = 0; i < sets.getLength(); i++) {
            int order = parseInt(attribute((Element) sets.item(i), "order", ""), -1);
            if (order >= 0) {
                minZoom = Math.min(minZoom, order);
                maxZoom = Math.max(maxZoom, order);
            }
        }
        if (minZoom == Integer.MAX_VALUE) {
            minZoom = 0;
            maxZoom = 18;
        }

        double[] boundingBox = null;
        Element box = first(root, "BoundingBox");
        if (box != null) {
            double[] values = {
                parseDouble(attribute(box, "minx", "")),
                parseDouble(attribute(box, "miny", "")),
                parseDouble(attribute(box, "maxx", "")),
                parseDouble(attribute(box, "maxy", ""))
            };
            boolean finite = true;
            for (double value : values) {
                finite &= !Double.isNaN(value) && !Double.isInfinite(value);
            }
            if (finite) {
                boundingBox = values;
            }
        }

        return new TmsMetadata(srs, profile, extension.toLowerCase(Locale.ROOT), mimeType, tileWidth, tileHeight,
                minZoom, maxZoom, boundingBox);
    }

    /** E.g. {@code EPSG:3857} or {@code EPSG:4326}; may be empty. */
    public String srs() {
        return srs;
    }

    /** E.g. {@code mercator}, {@code global-mercator}, {@code geodetic}; may be empty. */
    public String profile() {
        return profile;
    }

    /**
     * Whether tiles use the geographic (EPSG:4326, two root tiles) scheme
     * rather than Web Mercator.
     */
    public boolean isGeographic() {
        String p = profile.toLowerCase(Locale.ROOT);
        String s = srs.toUpperCase(Locale.ROOT);
        return p.contains("geodetic") || s.contains("4326") || s.contains("CRS:84");
    }

    public String extension() {
        return extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public int tileWidth() {
        return tileWidth;
    }

    public int tileHeight() {
        return tileHeight;
    }

    public int minZoom() {
        return minZoom;
    }

    public int maxZoom() {
        return maxZoom;
    }

    /** {@code [minx, miny, maxx, maxy]} in the SRS units, or {@code null}. */
    public double[] boundingBox() {
        return boundingBox == null ? null : boundingBox.clone();
    }

    private static DocumentBuilderFactory hardenedFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        // Android's parser rejects some of these feature names; the DOCTYPE
        // check above is the guarantee, these are defense in depth. Each is
        // set directly (not through a helper) so static analysis sees them.
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception unsupported) {
            // Not supported on this platform.
        }
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        } catch (Exception unsupported) {
            // Not supported on this platform.
        }
        try {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception unsupported) {
            // Not supported on this platform.
        }
        try {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception unsupported) {
            // Not supported on this platform.
        }
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (Exception unsupported) {
            // Not supported on this platform.
        }
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (Exception unsupported) {
            // Not supported on this platform.
        }
        try {
            factory.setXIncludeAware(false);
        } catch (UnsupportedOperationException unsupported) {
            // Not available on every platform; XInclude is off by default.
        }
        return factory;
    }

    private static Element first(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static String text(Element parent, String name) {
        Element element = first(parent, name);
        return element == null ? "" : element.getTextContent().trim();
    }

    private static String attribute(Element element, String name, String fallback) {
        String value = element.getAttribute(name);
        return value == null || value.isEmpty() ? fallback : value.trim();
    }

    private static String localName(Element element) {
        String name = element.getTagName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException invalid) {
            return Double.NaN;
        }
    }
}
