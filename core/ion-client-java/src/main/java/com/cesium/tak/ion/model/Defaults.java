package com.cesium.tak.ion.model;

import java.util.Collections;
import java.util.List;

/**
 * Global datasets advertised by {@code GET /v1/defaults}: the default
 * imagery, terrain, and buildings, plus the "quick add" list (for example
 * Google Photorealistic 3D Tiles). Ids are {@code -1} when not reported.
 */
public final class Defaults {
    private final long imageryAssetId;
    private final long terrainAssetId;
    private final long buildingsAssetId;
    private final List<QuickAddAsset> quickAddAssets;

    public Defaults(long imageryAssetId, long terrainAssetId, long buildingsAssetId, List<QuickAddAsset> quickAddAssets) {
        this.imageryAssetId = imageryAssetId;
        this.terrainAssetId = terrainAssetId;
        this.buildingsAssetId = buildingsAssetId;
        this.quickAddAssets = Collections.unmodifiableList(quickAddAssets);
    }

    public long imageryAssetId() {
        return imageryAssetId;
    }

    public long terrainAssetId() {
        return terrainAssetId;
    }

    public long buildingsAssetId() {
        return buildingsAssetId;
    }

    public List<QuickAddAsset> quickAddAssets() {
        return quickAddAssets;
    }

    /** A globally available dataset. */
    public static final class QuickAddAsset {
        private final long assetId;
        private final String name;
        private final String description;
        private final AssetType type;
        private final boolean subscribed;

        public QuickAddAsset(long assetId, String name, String description, String rawType, boolean subscribed) {
            this.assetId = assetId;
            this.name = name == null ? "Asset " + assetId : name;
            this.description = description == null ? "" : description;
            this.type = AssetType.fromIon(rawType);
            this.subscribed = subscribed;
        }

        public long assetId() {
            return assetId;
        }

        public String name() {
            return name;
        }

        public String description() {
            return description;
        }

        public AssetType type() {
            return type;
        }

        /** Whether the account has added this asset (required to stream it). */
        public boolean isSubscribed() {
            return subscribed;
        }
    }
}
