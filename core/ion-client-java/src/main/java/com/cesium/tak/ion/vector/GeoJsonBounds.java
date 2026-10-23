package com.cesium.tak.ion.vector;

import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonException;
import com.cesium.tak.ion.tiles.TilesetBounds;

import java.util.List;
import java.util.Map;

/** The geographic extent of a GeoJSON document, so a viewer can fly to it. */
public final class GeoJsonBounds {
    private double west = Double.POSITIVE_INFINITY;
    private double south = Double.POSITIVE_INFINITY;
    private double east = Double.NEGATIVE_INFINITY;
    private double north = Double.NEGATIVE_INFINITY;

    private GeoJsonBounds() {
    }

    /**
     * Uses {@code bbox} when present, otherwise every position under a
     * {@code coordinates} member (longitude, latitude order, per RFC 7946).
     *
     * @return {@code null} for malformed documents or ones without positions
     */
    public static TilesetBounds of(String geoJson) {
        Object root;
        try {
            root = Json.parse(geoJson);
        } catch (JsonException malformed) {
            return null;
        }
        GeoJsonBounds bounds = new GeoJsonBounds();
        if (root instanceof Map) {
            Object bbox = ((Map<?, ?>) root).get("bbox");
            if (bbox instanceof List && ((List<?>) bbox).size() >= 4) {
                List<?> box = (List<?>) bbox;
                int half = box.size() / 2;
                bounds.add(box.get(0), box.get(1));
                bounds.add(box.get(half), box.get(half + 1));
            }
        }
        if (bounds.isEmpty()) {
            bounds.walk(root, false);
        }
        if (bounds.isEmpty()) {
            return null;
        }
        return TilesetBounds.fromDegrees(bounds.west, bounds.south, bounds.east, bounds.north);
    }

    private void walk(Object node, boolean inCoordinates) {
        if (node instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) node).entrySet()) {
                walk(entry.getValue(), "coordinates".equals(entry.getKey()));
            }
        } else if (node instanceof List) {
            List<?> list = (List<?>) node;
            if (inCoordinates && list.size() >= 2 && list.get(0) instanceof Number && list.get(1) instanceof Number) {
                add(list.get(0), list.get(1));
                return;
            }
            for (Object element : list) {
                walk(element, inCoordinates);
            }
        }
    }

    private void add(Object longitude, Object latitude) {
        if (!(longitude instanceof Number) || !(latitude instanceof Number)) {
            return;
        }
        double lon = ((Number) longitude).doubleValue();
        double lat = ((Number) latitude).doubleValue();
        if (Double.isNaN(lon) || Double.isNaN(lat) || Math.abs(lon) > 180 || Math.abs(lat) > 90) {
            return;
        }
        west = Math.min(west, lon);
        east = Math.max(east, lon);
        south = Math.min(south, lat);
        north = Math.max(north, lat);
    }

    private boolean isEmpty() {
        return west > east;
    }
}
