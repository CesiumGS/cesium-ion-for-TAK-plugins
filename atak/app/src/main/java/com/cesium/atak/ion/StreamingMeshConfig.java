package com.cesium.atak.ion;

import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonException;
import com.cesium.tak.ion.json.JsonObject;

/**
 * The small JSON document ATAK's "Add Content from Service" path imports to
 * stream a 3D Tiles tileset. Handing the tileset URL to ModelImporter
 * directly does not stream in ATAK 5.7 (finding from the
 * jastman-fix-atak-google-tiles branch).
 */
final class StreamingMeshConfig {
    /** Import content type registered by ATAK's StreamingMeshServiceImportUtil. */
    static final String CONTENT_TYPE = "Streaming Mesh Configuration";
    static final String MIME_TYPE = "application/json";
    static final String MODEL_TYPE = "Cesium3DTiles";
    /**
     * Content type of the "3D Model" layer ATAK's ModelImporter creates from
     * the configuration; ATAK keeps that layer, keyed by the tileset URI,
     * until it is deleted under this content type.
     */
    static final String MODEL_CONTENT_TYPE = "3D Model";

    private StreamingMeshConfig() {
    }

    static String json(String tilesetUrl, String name) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"ModelInfo\":{\"uri\":");
        Json.writeString(out, tilesetUrl);
        out.append(",\"name\":");
        Json.writeString(out, name);
        out.append(",\"type\":");
        Json.writeString(out, MODEL_TYPE);
        out.append("}}");
        return out.toString();
    }

    /** @return the tileset URI in a document written by {@link #json}, or {@code null} */
    static String tilesetUri(String configJson) {
        try {
            JsonObject modelInfo = Json.parseObject(configJson).optObject("ModelInfo");
            return modelInfo == null ? null : modelInfo.optNonBlank("uri");
        } catch (JsonException | RuntimeException unreadable) {
            return null;
        }
    }

    /** A file name that is stable per asset, so re-imports replace rather than duplicate. */
    static String fileName(long assetId) {
        return "cesium-ion-" + assetId + ".json";
    }
}
