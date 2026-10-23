package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cesium.tak.ion.json.JsonException;
import com.cesium.tak.ion.tiles.AttributionCollector;
import com.cesium.tak.ion.tiles.TileContent;
import com.cesium.tak.ion.tiles.TileUrls;
import com.cesium.tak.ion.tiles.TilesetUriRewriter;
import com.cesium.tak.ion.tiles.TmsMetadata;
import com.cesium.tak.ion.util.Secret;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TilesTest {

    static byte[] glb(String json) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int padded = (jsonBytes.length + 3) & ~3;
        ByteBuffer buffer = ByteBuffer.allocate(12 + 8 + padded).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x46546C67).putInt(2).putInt(12 + 8 + padded);
        buffer.putInt(padded).putInt(0x4E4F534A).put(jsonBytes);
        while (buffer.hasRemaining()) {
            buffer.put((byte) ' ');
        }
        return buffer.array();
    }

    static byte[] b3dm(byte[] glb, String featureTableJson) {
        byte[] featureTable = featureTableJson.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(28 + featureTable.length + glb.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("b3dm".getBytes(StandardCharsets.US_ASCII)).putInt(1).putInt(buffer.capacity());
        buffer.putInt(featureTable.length).putInt(0).putInt(0).putInt(0);
        buffer.put(featureTable).put(glb);
        return buffer.array();
    }

    @Test
    void readsGoogleStyleGlbHeader() {
        byte[] tile = glb("{\"asset\":{\"version\":\"2.0\",\"copyright\":\"Google;Landsat / Copernicus; \"},"
                + "\"extensionsUsed\":[\"KHR_draco_mesh_compression\",\"KHR_materials_unlit\"],"
                + "\"extensionsRequired\":[\"KHR_draco_mesh_compression\"]}");
        assertEquals(TileContent.Format.GLB, TileContent.sniff(tile));
        TileContent.GltfInfo info = TileContent.gltfInfo(tile);
        assertEquals(List.of("Google", "Landsat / Copernicus"), info.copyrightEntries());
        assertEquals(List.of("KHR_draco_mesh_compression"), info.extensionsRequired());
        assertEquals("2.0", info.version());
    }

    @Test
    void readsGlbEmbeddedInB3dm() {
        byte[] tile = b3dm(glb("{\"asset\":{\"version\":\"2.0\",\"copyright\":\"Vexcel\"}}"), "{\"BATCH_LENGTH\":0}  ");
        assertEquals(TileContent.Format.B3DM, TileContent.sniff(tile));
        assertEquals("Vexcel", TileContent.gltfInfo(tile).copyright());
    }

    @Test
    void ignoresNonGltfAndTruncatedContent() {
        assertNull(TileContent.gltfInfo("{\"asset\":{}}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(TileContent.Format.JSON, TileContent.sniff("  {\"asset\":{}}".getBytes(StandardCharsets.UTF_8)));
        byte[] truncated = glb("{\"asset\":{}}");
        byte[] cut = new byte[24];
        System.arraycopy(truncated, 0, cut, 0, cut.length);
        assertNull(TileContent.gltfInfo(cut));
        assertNull(TileContent.gltfInfo(new byte[0]));
    }

    @Test
    void ordersCreditsByFrequency() {
        AttributionCollector collector = new AttributionCollector();
        collector.recordTile(glb("{\"asset\":{\"copyright\":\"Airbus;Google\"}}"));
        collector.recordTile(glb("{\"asset\":{\"copyright\":\"Google\"}}"));
        collector.recordTile(glb("{\"asset\":{\"copyright\":\"Landsat;Google;Airbus\"}}"));
        collector.recordTile("not a tile".getBytes(StandardCharsets.UTF_8));
        assertEquals("Google; Airbus; Landsat", collector.creditLine());
        collector.reset();
        assertEquals("", collector.creditLine());
    }

    @Test
    void rewritesOnlyReferencesAndKeepsNumbersExact() throws JsonException {
        String json = "{\"root\":{\"transform\":[1215011.9317263428,-4736309.3434217675],\"geometricError\":1e+100,"
                + "\"content\":{\"uri\":\"/v1/a.json?session=S\"},\"children\":[{\"content\":{\"uri\":\"b\\/c.glb\"}}],"
                + "\"extras\":{\"name\":\"uri\",\"values\":[\"/v1/not-a-ref\"]}}}";
        TilesetUriRewriter.Result result = TilesetUriRewriter.rewrite(json, reference -> "/proxy" + reference);
        assertEquals(List.of("/v1/a.json?session=S", "b/c.glb"), result.references());
        assertEquals(2, result.rewrittenCount());
        assertEquals(json.replace("\"/v1/a.json?session=S\"", "\"/proxy/v1/a.json?session=S\"")
                .replace("\"b\\/c.glb\"", "\"/proxyb/c.glb\""), result.json());
        assertTrue(result.json().contains("1215011.9317263428,-4736309.3434217675"));
    }

    @Test
    void clampsOnlyGeometricErrorsAboveTheCeiling() throws JsonException {
        String json = "{\"geometricError\":1e+100,\"root\":{\"geometricError\":1E100,\"box\":[1e+100],"
                + "\"children\":[{\"geometricError\":250.5,\"extras\":{\"geometricErrorNote\":1e+100}}]}}";
        TilesetUriRewriter.Result result = TilesetUriRewriter.rewrite(json, reference -> null, 300000);
        assertEquals(2, result.clampedCount());
        assertEquals(1e100, result.maxGeometricError());
        assertEquals("{\"geometricError\":300000,\"root\":{\"geometricError\":300000,\"box\":[1e+100],"
                + "\"children\":[{\"geometricError\":250.5,\"extras\":{\"geometricErrorNote\":1e+100}}]}}",
                result.json());
        assertEquals("{\"geometricError\":25000000}",
                TilesetUriRewriter.rewrite("{\"geometricError\":9e99}", r -> null, 2.5e7).json());
        assertEquals("{\"geometricError\":0.5}",
                TilesetUriRewriter.rewrite("{\"geometricError\":9e99}", r -> null, 0.5).json());
        assertThrows(IllegalArgumentException.class, () -> TilesetUriRewriter.rewrite("{}", r -> null, 0));
        assertThrows(IllegalArgumentException.class, () -> TilesetUriRewriter.rewrite("{}", r -> null, Double.NaN));
    }

    @Test
    void rewriterRejectsBrokenDocuments() {
        assertThrows(JsonException.class, () -> TilesetUriRewriter.references("{\"root\":{\"uri\":\"a\"}"));
        assertThrows(JsonException.class, () -> TilesetUriRewriter.references("{\"root\":[}"));
        assertThrows(JsonException.class, () -> TilesetUriRewriter.references("{\"uri\":\"unterminated}"));
    }

    @Test
    void splitsAndReappliesCredentials() {
        TileUrls.CredentialSplit split = TileUrls.splitCredentials(
                "https://tile.googleapis.com/v1/3dtiles/root.json?key=K&v=1&session=S");
        assertEquals("https://tile.googleapis.com/v1/3dtiles/root.json?v=1", split.url());
        assertEquals(List.of("key", "session"), List.copyOf(split.credentials().keySet()));
        assertEquals("https://x.example.com/a?b=1&key=a%2Bb%2Fc",
                TileUrls.withQueryParameters("https://x.example.com/a?b=1", Map.of("key", Secret.of("a+b/c"))));
        assertEquals("https://x.example.com/a?key=mine",
                TileUrls.withQueryParameters("https://x.example.com/a?key=mine", Map.of("key", Secret.of("other"))));
    }

    @Test
    void parsesTmsMetadata() {
        String xml = "<?xml version=\"1.0\"?><TileMap version=\"1.0.0\"><SRS>EPSG:4326</SRS>"
                + "<BoundingBox minx=\"-180\" miny=\"-90\" maxx=\"180\" maxy=\"90\"/>"
                + "<TileFormat width=\"256\" height=\"256\" mime-type=\"image/jpeg\" extension=\"JPG\"/>"
                + "<TileSets profile=\"geodetic\"><TileSet href=\"0\" order=\"0\"/><TileSet href=\"14\" order=\"14\"/>"
                + "<TileSet href=\"3\" order=\"3\"/></TileSets></TileMap>";
        TmsMetadata metadata = TmsMetadata.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertEquals("jpg", metadata.extension());
        assertEquals(0, metadata.minZoom());
        assertEquals(14, metadata.maxZoom());
        assertTrue(metadata.isGeographic());
        assertArrayEquals(new double[] {-180, -90, 180, 90}, metadata.boundingBox());
    }

    @Test
    void tmsMercatorDefaults() {
        String xml = "<TileMap><SRS>EPSG:3857</SRS><TileSets profile=\"mercator\"></TileSets></TileMap>";
        TmsMetadata metadata = TmsMetadata.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertFalse(metadata.isGeographic());
        assertEquals("png", metadata.extension());
        assertEquals(18, metadata.maxZoom());
        assertNull(metadata.boundingBox());
    }

    @Test
    void tmsRejectsDoctypeAndGarbage() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE TileMap [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<TileMap><SRS>&x;</SRS></TileMap>";
        assertThrows(IllegalArgumentException.class, () -> TmsMetadata.parse(xxe.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> TmsMetadata.parse("<html></html>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> TmsMetadata.parse("not xml".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void secretsNeverPrint() {
        Secret secret = Secret.of("  real-token  ");
        assertEquals("real-token", secret.reveal());
        assertFalse(secret.toString().contains("real-token"));
        assertThrows(IllegalArgumentException.class, () -> Secret.of("two words"));
        assertThrows(IllegalArgumentException.class, () -> Secret.of("line\nbreak"));
        assertThrows(IllegalArgumentException.class, () -> Secret.of(" "));
        assertNull(Secret.ofNullable(" "));
    }
}
