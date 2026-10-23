package com.cesium.tak.ion.model;

/** An entry from {@code GET /v1/assets}. */
public final class IonAsset {
    private final long id;
    private final String name;
    private final String description;
    private final AssetType type;
    private final String rawType;
    private final AssetStatus status;
    private final int percentComplete;
    private final long bytes;
    private final String dateAdded;
    private final String attribution;

    public IonAsset(
            long id,
            String name,
            String description,
            String rawType,
            AssetStatus status,
            int percentComplete,
            long bytes,
            String dateAdded,
            String attribution) {
        this.id = id;
        this.name = name == null || name.trim().isEmpty() ? "Asset " + id : name.trim();
        this.description = description == null ? "" : description;
        this.rawType = rawType == null ? "" : rawType;
        this.type = AssetType.fromIon(rawType);
        this.status = status == null ? AssetStatus.UNKNOWN : status;
        this.percentComplete = percentComplete;
        this.bytes = bytes;
        this.dateAdded = dateAdded == null ? "" : dateAdded;
        this.attribution = attribution == null ? "" : attribution;
    }

    public long id() {
        return id;
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

    /** The type string as ion reported it, for display when {@link #type()} is UNKNOWN. */
    public String rawType() {
        return rawType;
    }

    public AssetStatus status() {
        return status;
    }

    public int percentComplete() {
        return percentComplete;
    }

    public long bytes() {
        return bytes;
    }

    public String dateAdded() {
        return dateAdded;
    }

    public String attribution() {
        return attribution;
    }

    /** Whether this asset can be loaded into TAK now. */
    public boolean isLoadable() {
        // Some deployments omit status for Asset Depot assets; only reject
        // states that are known to be unusable.
        boolean usableStatus = status == AssetStatus.COMPLETE || status == AssetStatus.UNKNOWN;
        return usableStatus && type.isSupportedInTak();
    }

    @Override
    public String toString() {
        return "IonAsset[" + id + ", " + rawType + ", " + status + ", " + name + "]";
    }
}
