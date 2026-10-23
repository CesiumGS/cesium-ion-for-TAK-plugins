package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.cesium.tak.ion.tiles.TilesetBounds;
import com.cesium.tak.ion.vector.GeoJsonBounds;

import org.junit.jupiter.api.Test;

class GeoJsonBoundsTest {
    @Test
    void walksEveryPositionInAFeatureCollection() {
        TilesetBounds bounds = GeoJsonBounds.of("{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[-75.2,39.9]},"
                + "\"properties\":{\"values\":[1,2]}},"
                + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":"
                + "[[[-75.1,40.0,12],[-75.15,39.95],[-75.1,40.0]]]}}]}");
        assertNotNull(bounds);
        assertEquals(39.95, bounds.latitude(), 1e-9);
        assertEquals(-75.15, bounds.longitude(), 1e-9);
    }

    @Test
    void prefersTheBbox() {
        TilesetBounds bounds = GeoJsonBounds.of("{\"type\":\"Feature\",\"bbox\":[10,20,0,30,40,100],"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[0,0]}}");
        assertNotNull(bounds);
        assertEquals(30, bounds.latitude(), 1e-9);
        assertEquals(20, bounds.longitude(), 1e-9);
    }

    @Test
    void nothingToLocate() {
        assertNull(GeoJsonBounds.of("{\"type\":\"FeatureCollection\",\"features\":[]}"));
        assertNull(GeoJsonBounds.of("not json"));
        assertNull(GeoJsonBounds.of("{\"properties\":{\"values\":[1,2]}}"));
    }
}
