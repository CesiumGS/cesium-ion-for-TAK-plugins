package com.cesium.atak.ion;

import com.cesium.tak.ion.model.AssetType;

/** Names and file names for what the plugin hands ATAK. No Android or ATAK types, so it is unit-tested. */
final class LayerNames {
    private LayerNames() {
    }

    /** A valid file name built from an asset name. */
    static String safeName(String assetName, String fallback) {
        String safe = assetName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        return safe.isEmpty() ? fallback : safe;
    }

    /**
     * ATAK names each imagery layer's tile cache file after the layer, so the
     * name must be a valid file name (a colon made the cache fail to open).
     */
    static String imageryLayerName(String assetName) {
        return "Cesium ion - " + safeName(assetName, "imagery");
    }

    /** {@code .geojson}, {@code .kml}, or {@code .kmz} (zip content). */
    static String vectorExtension(AssetType type, byte[] body) {
        if (type == AssetType.GEOJSON) {
            return ".geojson";
        }
        boolean zip = body.length >= 4 && body[0] == 'P' && body[1] == 'K' && body[2] == 3 && body[3] == 4;
        return zip ? ".kmz" : ".kml";
    }
}
