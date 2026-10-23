package com.cesium.tak.ion.tiles;

import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonException;
import com.cesium.tak.ion.json.JsonObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Identifies 3D Tiles content and reads the glTF header of GLB-based tiles.
 *
 * <p>Used to collect Google's per-tile copyright strings (which must be shown
 * while tiles are visible), and by the probe to report which glTF extensions
 * a renderer must support.
 */
public final class TileContent {
    /** Tile payload formats, from their magic bytes. */
    public enum Format {
        GLB,
        B3DM,
        I3DM,
        PNTS,
        CMPT,
        SUBTREE,
        JSON,
        UNKNOWN
    }

    private static final int GLB_MAGIC = 0x46546C67; // "glTF"
    private static final int GLB_JSON_CHUNK = 0x4E4F534A; // "JSON"
    private static final int B3DM_HEADER_BYTES = 28;
    private static final int I3DM_HEADER_BYTES = 32;

    private TileContent() {
    }

    public static Format sniff(byte[] content) {
        if (content == null || content.length < 4) {
            return Format.UNKNOWN;
        }
        String magic = new String(content, 0, 4, StandardCharsets.US_ASCII);
        switch (magic) {
            case "glTF":
                return Format.GLB;
            case "b3dm":
                return Format.B3DM;
            case "i3dm":
                return Format.I3DM;
            case "pnts":
                return Format.PNTS;
            case "cmpt":
                return Format.CMPT;
            case "subt":
                return Format.SUBTREE;
            default:
                for (byte b : content) {
                    if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                        continue;
                    }
                    return b == '{' ? Format.JSON : Format.UNKNOWN;
                }
                return Format.UNKNOWN;
        }
    }

    /**
     * The glTF header of a GLB, B3DM, or embedded-glTF I3DM tile, or
     * {@code null} for other content or malformed input.
     */
    public static GltfInfo gltfInfo(byte[] content) {
        Format format = sniff(content);
        int glbOffset;
        switch (format) {
            case GLB:
                glbOffset = 0;
                break;
            case B3DM:
                glbOffset = embeddedGlbOffset(content, B3DM_HEADER_BYTES);
                break;
            case I3DM:
                if (content.length < I3DM_HEADER_BYTES || readInt(content, 28) != 1) {
                    return null; // gltfFormat 0 means an external glTF URI.
                }
                glbOffset = embeddedGlbOffset(content, I3DM_HEADER_BYTES);
                break;
            default:
                return null;
        }
        if (glbOffset < 0 || glbOffset + 20 > content.length || readInt(content, glbOffset) != GLB_MAGIC) {
            return null;
        }
        int chunkLength = readInt(content, glbOffset + 12);
        int chunkType = readInt(content, glbOffset + 16);
        int chunkStart = glbOffset + 20;
        if (chunkType != GLB_JSON_CHUNK || chunkLength < 0 || chunkStart + (long) chunkLength > content.length) {
            return null;
        }
        try {
            String text = new String(content, chunkStart, chunkLength, StandardCharsets.UTF_8).trim();
            JsonObject gltf = Json.parseObject(text);
            JsonObject asset = gltf.optObject("asset");
            return new GltfInfo(
                    asset.optString("copyright", ""),
                    asset.optString("generator", ""),
                    asset.optString("version", ""),
                    gltf.optStrings("extensionsUsed"),
                    gltf.optStrings("extensionsRequired"));
        } catch (JsonException malformed) {
            return null;
        }
    }

    private static int embeddedGlbOffset(byte[] content, int headerBytes) {
        if (content.length < headerBytes) {
            return -1;
        }
        long offset = headerBytes;
        // Feature table JSON/binary and batch table JSON/binary byte lengths.
        for (int field = 12; field < 28; field += 4) {
            long length = readInt(content, field) & 0xffffffffL;
            offset += length;
        }
        return offset > Integer.MAX_VALUE ? -1 : (int) offset;
    }

    private static int readInt(byte[] content, int offset) {
        return ByteBuffer.wrap(content, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** Header fields of a glTF 2.0 asset. */
    public static final class GltfInfo {
        private final String copyright;
        private final String generator;
        private final String version;
        private final List<String> extensionsUsed;
        private final List<String> extensionsRequired;

        GltfInfo(String copyright, String generator, String version, List<String> extensionsUsed,
                List<String> extensionsRequired) {
            this.copyright = copyright;
            this.generator = generator;
            this.version = version;
            this.extensionsUsed = Collections.unmodifiableList(extensionsUsed);
            this.extensionsRequired = Collections.unmodifiableList(extensionsRequired);
        }

        /** {@code asset.copyright}, possibly several entries separated by {@code ;}. */
        public String copyright() {
            return copyright;
        }

        /** The individual copyright holders in {@link #copyright()}. */
        public List<String> copyrightEntries() {
            List<String> entries = new ArrayList<>();
            for (String entry : copyright.split(";")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    entries.add(trimmed);
                }
            }
            return entries;
        }

        public String generator() {
            return generator;
        }

        public String version() {
            return version;
        }

        public List<String> extensionsUsed() {
            return extensionsUsed;
        }

        /** Extensions a renderer must support to draw this tile at all. */
        public List<String> extensionsRequired() {
            return extensionsRequired;
        }
    }
}
