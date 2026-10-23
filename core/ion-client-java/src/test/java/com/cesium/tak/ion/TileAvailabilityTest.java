package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cesium.tak.ion.terrain.QuantizedMesh;
import com.cesium.tak.ion.terrain.TerrainLayout;
import com.cesium.tak.ion.terrain.TileAvailability;
import com.cesium.tak.ion.terrain.TileAvailability.Status;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

class TileAvailabilityTest {
    /** Like Cesium ion Self-Hosted terrain: no "available" list, availability in tile metadata. */
    private static final String METADATA_LAYER = "{\"format\":\"quantized-mesh-1.0\",\"scheme\":\"tms\","
            + "\"tiles\":[\"{z}/{x}/{y}.terrain?v={version}\"],\"version\":\"1.1.0\",\"minzoom\":0,\"maxzoom\":12,"
            + "\"extensions\":[\"metadata\",\"octvertexnormals\"],\"metadataAvailability\":10}";

    @Test
    void metadataLayoutsStartWithOnlyLevelZeroKnown() {
        TerrainLayout layout = TerrainLayout.parse(METADATA_LAYER);
        TileAvailability availability = new TileAvailability(layout);
        assertEquals(10, layout.metadataAvailability());
        assertTrue(availability.usesMetadata());
        assertTrue(availability.carriesMetadata(0));
        assertTrue(availability.carriesMetadata(10));
        assertFalse(availability.carriesMetadata(8));
        assertEquals(Status.AVAILABLE, availability.status(0, 1, 0));
        assertEquals(Status.UNKNOWN, availability.status(8, 149, 184));
        assertEquals(Status.UNAVAILABLE, availability.status(13, 0, 0)); // beyond maxzoom
    }

    @Test
    void metadataParentsAreTheAncestorsAtMultiplesOfTheStep() {
        TileAvailability availability = new TileAvailability(TerrainLayout.parse(METADATA_LAYER));
        assertArrayEquals(new int[] {0, 0, 0}, availability.metadataParent(8, 149, 184));
        assertArrayEquals(new int[] {0, 0, 0}, availability.metadataParent(10, 596, 739));
        assertArrayEquals(new int[] {0, 1, 0}, availability.metadataParent(10, 1500, 600));
        assertArrayEquals(new int[] {10, 596, 739}, availability.metadataParent(12, 2385, 2957));
    }

    @Test
    void tileMetadataDecidesItsDescendants() {
        TileAvailability availability = new TileAvailability(TerrainLayout.parse(METADATA_LAYER));
        // Level-0 tile 0/0/0 lists level 1-2 tiles; nothing at level 3+.
        String json = "{\"available\":[[{\"startX\":0,\"startY\":0,\"endX\":1,\"endY\":1}],"
                + "[{\"startX\":1,\"startY\":2,\"endX\":2,\"endY\":3}]]}";
        List<List<TerrainLayout.Range>> ranges = QuantizedMesh.metadataAvailability(tile(json, 3), 0);
        assertEquals(2, ranges.size());
        assertEquals(1, ranges.get(0).get(0).level);
        availability.addMetadata(0, 0, 0, ranges);
        assertEquals(Status.AVAILABLE, availability.status(2, 2, 3));
        assertEquals(Status.UNAVAILABLE, availability.status(2, 0, 0));
        assertEquals(Status.UNAVAILABLE, availability.status(8, 0, 0)); // listed by 0/0/0: absent
        assertEquals(Status.UNAVAILABLE, availability.status(12, 5, 5)); // under an absent level-10 tile
        assertEquals(Status.UNKNOWN, availability.status(8, 300, 100)); // under 0/1/0, not read yet
    }

    @Test
    void extensionsAreFoundAfterTheMeshAndCanBeStripped() {
        String json = "{\"available\":[]}";
        for (int vertices : new int[] {3, 4}) { // odd and even vertex data lengths (index alignment)
            byte[] tile = tile(json, vertices);
            byte[] plain = QuantizedMesh.withoutExtensions(tile);
            assertTrue(plain.length < tile.length);
            assertEquals(0, QuantizedMesh.metadataAvailability(plain, 0).size());
            assertEquals(QuantizedMesh.withoutExtensions(plain).length, plain.length);
        }
    }

    @Test
    void malformedTilesHaveNoMetadata() {
        assertEquals(0, QuantizedMesh.metadataAvailability(new byte[10], 0).size());
        byte[] truncated = new byte[92];
        ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN).putInt(88, 1_000_000);
        assertEquals(0, QuantizedMesh.metadataAvailability(truncated, 0).size());
        assertEquals(92, QuantizedMesh.withoutExtensions(truncated).length);
    }

    @Test
    void layoutsWithAnAvailableListDoNotUseMetadata() {
        TerrainLayout layout = TerrainLayout.parse("{\"tiles\":[\"{z}/{x}/{y}.terrain\"],\"maxzoom\":2,"
                + "\"available\":[[{\"startX\":0,\"startY\":0,\"endX\":1,\"endY\":0}],"
                + "[{\"startX\":0,\"startY\":0,\"endX\":0,\"endY\":0}]]}");
        TileAvailability availability = new TileAvailability(layout);
        assertFalse(availability.usesMetadata());
        assertEquals(Status.AVAILABLE, availability.status(1, 0, 0));
        assertEquals(Status.UNAVAILABLE, availability.status(1, 3, 1));
        assertEquals(Status.AVAILABLE, availability.status(2, 7, 3)); // unlisted level: whole level
    }

    /** A minimal quantized-mesh tile with a normals extension, then a metadata extension. */
    private static byte[] tile(String metadataJson, int vertices) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[88], 0, 88);
        writeInt(out, vertices);
        out.write(new byte[6 * vertices], 0, 6 * vertices);
        if (out.size() % 2 != 0) {
            out.write(0);
        }
        writeInt(out, 1);
        out.write(new byte[6], 0, 6); // three uint16 indices
        for (int edge = 0; edge < 4; edge++) {
            writeInt(out, 0);
        }
        out.write(1); // oct-encoded normals
        writeInt(out, 2 * vertices);
        out.write(new byte[2 * vertices], 0, 2 * vertices);
        byte[] json = metadataJson.getBytes(StandardCharsets.UTF_8);
        out.write(4); // metadata
        writeInt(out, 4 + json.length);
        writeInt(out, json.length);
        out.write(json, 0, json.length);
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
        out.write(bytes, 0, 4);
    }
}
