package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.cesium.tak.ion.tiles.TilesetBounds;

import org.junit.jupiter.api.Test;

class TilesetBoundsTest {
    @Test
    void regionCentreAndRadius() {
        double w = Math.toRadians(-75.2);
        double s = Math.toRadians(39.9);
        double e = Math.toRadians(-75.1);
        double n = Math.toRadians(40.0);
        TilesetBounds bounds = TilesetBounds.of("{\"root\":{\"boundingVolume\":{\"region\":["
                + w + "," + s + "," + e + "," + n + ",0,100]}}}");
        assertNotNull(bounds);
        assertEquals(39.95, bounds.latitude(), 1e-9);
        assertEquals(-75.15, bounds.longitude(), 1e-9);
        assertEquals(50, bounds.height(), 1e-9);
        assertEquals(5566, bounds.radius(), 10);
    }

    @Test
    void regionAcrossTheAntimeridian() {
        TilesetBounds bounds = TilesetBounds.of("{\"root\":{\"boundingVolume\":{\"region\":["
                + Math.toRadians(179) + ",0," + Math.toRadians(-179) + ",0.01,0,0]}}}");
        assertNotNull(bounds);
        assertEquals(180, Math.abs(bounds.longitude()), 1e-9);
    }

    @Test
    void sphereInEarthCentredCoordinates() {
        TilesetBounds bounds = TilesetBounds.of("{\"root\":{\"boundingVolume\":{\"sphere\":[6378137,0,0,100]}}}");
        assertNotNull(bounds);
        assertEquals(0, bounds.latitude(), 1e-9);
        assertEquals(0, bounds.longitude(), 1e-9);
        assertEquals(0, bounds.height(), 1e-3);
        assertEquals(100, bounds.radius(), 1e-9);
    }

    @Test
    void boxPlacedByTheRootTransform() {
        double[] ecef = ecef(45, 90, 200);
        String transform = "[1,0,0,0, 0,1,0,0, 0,0,1,0, " + ecef[0] + "," + ecef[1] + "," + ecef[2] + ",1]";
        TilesetBounds bounds = TilesetBounds.of("{\"root\":{\"transform\":" + transform
                + ",\"boundingVolume\":{\"box\":[0,0,0, 30,0,0, 0,40,0, 0,0,0]}}}");
        assertNotNull(bounds);
        assertEquals(45, bounds.latitude(), 1e-7);
        assertEquals(90, bounds.longitude(), 1e-7);
        assertEquals(200, bounds.height(), 1e-3);
        assertEquals(50, bounds.radius(), 1e-9);
    }

    @Test
    void localOrMissingVolumesHaveNoLocation() {
        assertNull(TilesetBounds.of("{\"root\":{\"boundingVolume\":{\"sphere\":[0,0,0,100]}}}"));
        assertNull(TilesetBounds.of("{\"root\":{}}"));
        assertNull(TilesetBounds.of("{\"asset\":{}}"));
        assertNull(TilesetBounds.of("not json"));
        assertNull(TilesetBounds.of("{\"root\":{\"boundingVolume\":{\"region\":[0,0,\"x\",0,0,0]}}}"));
    }

    private static double[] ecef(double latitudeDegrees, double longitudeDegrees, double height) {
        double a = 6378137.0;
        double e2 = 6.69437999014e-3;
        double lat = Math.toRadians(latitudeDegrees);
        double lon = Math.toRadians(longitudeDegrees);
        double n = a / Math.sqrt(1 - e2 * Math.sin(lat) * Math.sin(lat));
        return new double[] {
            (n + height) * Math.cos(lat) * Math.cos(lon),
            (n + height) * Math.cos(lat) * Math.sin(lon),
            (n * (1 - e2) + height) * Math.sin(lat)
        };
    }
}
