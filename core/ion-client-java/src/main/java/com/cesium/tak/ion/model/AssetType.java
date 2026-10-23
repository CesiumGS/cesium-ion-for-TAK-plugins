package com.cesium.tak.ion.model;

import java.util.Locale;

/** Cesium ion asset types, as reported in the {@code type} field. */
public enum AssetType {
    IMAGERY("IMAGERY", true),
    TERRAIN("TERRAIN", true),
    TILES_3D("3DTILES", true),
    GLTF("GLTF", false),
    CZML("CZML", false),
    KML("KML", false),
    GEOJSON("GEOJSON", false),
    UNKNOWN("UNKNOWN", false);

    private final String ionName;
    private final boolean supportedInTak;

    AssetType(String ionName, boolean supportedInTak) {
        this.ionName = ionName;
        this.supportedInTak = supportedInTak;
    }

    /** The name used by the ion REST API, e.g. {@code 3DTILES}. */
    public String ionName() {
        return ionName;
    }

    /** Whether the TAK plugins can stream this type into the TAK map. */
    public boolean isSupportedInTak() {
        return supportedInTak;
    }

    public static AssetType fromIon(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (AssetType type : values()) {
            if (type.ionName.equals(normalized)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
