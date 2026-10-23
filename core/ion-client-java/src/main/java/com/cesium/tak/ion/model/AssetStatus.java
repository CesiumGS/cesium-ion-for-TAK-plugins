package com.cesium.tak.ion.model;

import java.util.Locale;

/** Processing state of an ion asset. Only {@link #COMPLETE} assets can be streamed. */
public enum AssetStatus {
    AWAITING_FILES,
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETE,
    DATA_ERROR,
    ERROR,
    UNKNOWN;

    public static AssetStatus fromIon(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return UNKNOWN;
        }
    }
}
