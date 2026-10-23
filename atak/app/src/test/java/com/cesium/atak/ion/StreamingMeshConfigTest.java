package com.cesium.atak.ion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonObject;

import org.junit.Test;

public class StreamingMeshConfigTest {
    @Test
    public void writesTheModelInfoDocumentAtakImports() throws Exception {
        String json = StreamingMeshConfig.json(
                "http://127.0.0.1:8080/s/a/2275207/root.json?x=\"quoted\"", "Google \\ \"3D\" Tiles");
        JsonObject modelInfo = Json.parseObject(json).optObject("ModelInfo");
        assertEquals("http://127.0.0.1:8080/s/a/2275207/root.json?x=\"quoted\"", modelInfo.optString("uri", null));
        assertEquals("Google \\ \"3D\" Tiles", modelInfo.optString("name", null));
        assertEquals("Cesium3DTiles", modelInfo.optString("type", null));
    }

    @Test
    public void readsTheTilesetUriBackForRemoval() {
        String uri = "https://tile.googleapis.com/v1/3dtiles/root.json?key=K";
        assertEquals(uri, StreamingMeshConfig.tilesetUri(StreamingMeshConfig.json(uri, "Google")));
        assertNull(StreamingMeshConfig.tilesetUri("{\"ModelInfo\":{}}"));
        assertNull(StreamingMeshConfig.tilesetUri("not json"));
    }

    @Test
    public void fileNamesAreStablePerAsset() {
        assertEquals("cesium-ion-2275207.json", StreamingMeshConfig.fileName(2275207));
    }
}
