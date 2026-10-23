package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cesium.tak.ion.terrain.TerrainLayout;

import org.junit.jupiter.api.Test;

class TerrainLayoutTest {
    private static final String LAYER = "{\"tilejson\":\"2.1.0\",\"format\":\"quantized-mesh-1.0\","
            + "\"version\":\"1.2.0\",\"scheme\":\"tms\",\"projection\":\"EPSG:4326\","
            + "\"tiles\":[\"{z}/{x}/{y}.terrain?v={version}\"],\"minzoom\":0,\"maxzoom\":19,"
            + "\"extensions\":[\"octvertexnormals\",\"watermask\",\"metadata\"],"
            + "\"available\":[[{\"startX\":0,\"startY\":0,\"endX\":1,\"endY\":0}],"
            + "[{\"startX\":0,\"startY\":0,\"endX\":3,\"endY\":1}],"
            + "[{\"startX\":0,\"startY\":0,\"endX\":2,\"endY\":1},{\"startX\":5,\"startY\":2,\"endX\":7,\"endY\":3}]]}";

    @Test
    void resolvesTileUrlsAgainstTheEndpoint() {
        TerrainLayout layout = TerrainLayout.parse(LAYER);
        assertEquals("https://assets.ion.cesium.com/1/12/2345/1234.terrain?v=1.2.0",
                layout.tileUrl("https://assets.ion.cesium.com/1/", 12, 2345, 1234));
        assertEquals(0, layout.minZoom());
        assertEquals(19, layout.maxZoom());
    }

    @Test
    void availabilityFromTheListedLevelsThenWholeLevels() {
        TerrainLayout layout = TerrainLayout.parse(LAYER);
        assertTrue(layout.isAvailable(2, 6, 3));
        assertFalse(layout.isAvailable(2, 4, 3));
        assertEquals(2, layout.available(2).size());
        TerrainLayout.Range level5 = layout.available(5).get(0);
        assertEquals(63, level5.endX);
        assertEquals(31, level5.endY);
        assertTrue(layout.available(20).isEmpty());
    }

    @Test
    void rejectsOtherProjectionsAndMissingTemplates() {
        assertThrows(IllegalArgumentException.class,
                () -> TerrainLayout.parse("{\"projection\":\"EPSG:3857\",\"tiles\":[\"a\"]}"));
        assertThrows(IllegalArgumentException.class, () -> TerrainLayout.parse("{\"tiles\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> TerrainLayout.parse("nope"));
    }
}
