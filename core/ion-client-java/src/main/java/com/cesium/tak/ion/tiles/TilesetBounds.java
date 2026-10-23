package com.cesium.tak.ion.tiles;

import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonException;
import com.cesium.tak.ion.json.JsonObject;

import java.util.List;

/**
 * Where a tileset is: the geodetic centre and rough radius of its root
 * bounding volume, so a viewer can fly to a newly loaded asset.
 */
public final class TilesetBounds {
    private static final double WGS84_A = 6378137.0;
    private static final double WGS84_E2 = 6.69437999014e-3;
    /** Centres closer than this to the Earth's centre are local coordinates. */
    private static final double MIN_EARTH_RADIUS = 6.0e6;

    private final double latitude;
    private final double longitude;
    private final double height;
    private final double radius;

    private TilesetBounds(double latitude, double longitude, double height, double radius) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.height = height;
        this.radius = radius;
    }

    /** Degrees. */
    public double latitude() {
        return latitude;
    }

    /** Degrees, in [-180, 180]. */
    public double longitude() {
        return longitude;
    }

    /** Metres above the WGS84 ellipsoid. */
    public double height() {
        return height;
    }

    /** Metres; roughly half the extent of the bounding volume. */
    public double radius() {
        return radius;
    }

    /**
     * Reads the root bounding volume ({@code region}, {@code box}, or
     * {@code sphere}, honouring the root {@code transform}).
     *
     * @return {@code null} when the tileset has no georeferenced root volume
     */
    public static TilesetBounds of(String tilesetJson) {
        JsonObject root;
        try {
            root = Json.parseObject(tilesetJson).optObject("root");
        } catch (JsonException malformed) {
            return null;
        }
        if (root == null) {
            return null;
        }
        JsonObject volume = root.optObject("boundingVolume");
        if (volume == null) {
            return null;
        }
        double[] region = numbers(volume.optArray("region"), 6);
        if (region != null) {
            return fromRegion(region);
        }
        double[] transform = numbers(root.optArray("transform"), 16);
        double[] box = numbers(volume.optArray("box"), 12);
        if (box != null) {
            double extent = 0;
            for (int axis = 0; axis < 3; axis++) {
                double x = box[3 + axis * 3];
                double y = box[4 + axis * 3];
                double z = box[5 + axis * 3];
                extent += x * x + y * y + z * z;
            }
            return fromCartesian(box[0], box[1], box[2], Math.sqrt(extent) * scale(transform), transform);
        }
        double[] sphere = numbers(volume.optArray("sphere"), 4);
        if (sphere != null) {
            return fromCartesian(sphere[0], sphere[1], sphere[2], sphere[3] * scale(transform), transform);
        }
        return null;
    }

    /** The centre and radius of a geographic extent, in degrees. */
    public static TilesetBounds fromDegrees(double west, double south, double east, double north) {
        return fromRegion(new double[] {
            Math.toRadians(west), Math.toRadians(south), Math.toRadians(east), Math.toRadians(north), 0, 0});
    }

    private static TilesetBounds fromRegion(double[] region) {
        double west = region[0];
        double south = region[1];
        double east = region[2];
        double north = region[3];
        if (east < west) {
            east += 2 * Math.PI;
        }
        double latitude = (south + north) / 2;
        double longitude = (west + east) / 2;
        if (longitude > Math.PI) {
            longitude -= 2 * Math.PI;
        }
        double northSouth = (north - south) * WGS84_A;
        double eastWest = (east - west) * WGS84_A * Math.cos(latitude);
        double radius = Math.max(northSouth, eastWest) / 2;
        return new TilesetBounds(Math.toDegrees(latitude), Math.toDegrees(longitude), (region[4] + region[5]) / 2,
                radius);
    }

    private static TilesetBounds fromCartesian(double x, double y, double z, double radius, double[] transform) {
        if (transform != null) {
            // Column-major 4x4.
            double tx = transform[0] * x + transform[4] * y + transform[8] * z + transform[12];
            double ty = transform[1] * x + transform[5] * y + transform[9] * z + transform[13];
            double tz = transform[2] * x + transform[6] * y + transform[10] * z + transform[14];
            x = tx;
            y = ty;
            z = tz;
        }
        if (Math.sqrt(x * x + y * y + z * z) < MIN_EARTH_RADIUS) {
            return null;
        }
        double p = Math.sqrt(x * x + y * y);
        double longitude = Math.atan2(y, x);
        double latitude = Math.atan2(z, p * (1 - WGS84_E2));
        double height = 0;
        for (int i = 0; i < 6; i++) {
            double sin = Math.sin(latitude);
            double n = WGS84_A / Math.sqrt(1 - WGS84_E2 * sin * sin);
            height = p / Math.cos(latitude) - n;
            latitude = Math.atan2(z, p * (1 - WGS84_E2 * n / (n + height)));
        }
        return new TilesetBounds(Math.toDegrees(latitude), Math.toDegrees(longitude), height, radius);
    }

    /** The largest axis scale of the transform, or 1. */
    private static double scale(double[] transform) {
        if (transform == null) {
            return 1;
        }
        double largest = 0;
        for (int column = 0; column < 3; column++) {
            double x = transform[column * 4];
            double y = transform[column * 4 + 1];
            double z = transform[column * 4 + 2];
            largest = Math.max(largest, Math.sqrt(x * x + y * y + z * z));
        }
        return largest;
    }

    private static double[] numbers(List<Object> values, int count) {
        if (values.size() < count) {
            return null;
        }
        double[] result = new double[count];
        for (int i = 0; i < count; i++) {
            Object value = values.get(i);
            if (!(value instanceof Number)) {
                return null;
            }
            result[i] = ((Number) value).doubleValue();
            if (Double.isNaN(result[i]) || Double.isInfinite(result[i])) {
                return null;
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "TilesetBounds[%.5f, %.5f, h=%.0f m, r=%.0f m]",
                latitude, longitude, height, radius);
    }
}
