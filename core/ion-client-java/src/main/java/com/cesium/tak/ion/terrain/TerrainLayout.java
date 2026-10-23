package com.cesium.tak.ion.terrain;

import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonException;
import com.cesium.tak.ion.json.JsonObject;
import com.cesium.tak.ion.tiles.TileUrls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A quantized-mesh terrain's {@code layer.json}: where tiles are and which
 * exist. Tiles use the geographic TMS scheme: level 0 has two tiles (x 0-1,
 * y 0), and y counts up from the south.
 */
public final class TerrainLayout {
    /** An inclusive range of tiles at one level. */
    public static final class Range {
        public final int level;
        public final int startX;
        public final int startY;
        public final int endX;
        public final int endY;

        public Range(int level, int startX, int startY, int endX, int endY) {
            this.level = level;
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
        }

        boolean contains(int x, int y) {
            return x >= startX && x <= endX && y >= startY && y <= endY;
        }

        @Override
        public String toString() {
            return "L" + level + "[" + startX + ".." + endX + ", " + startY + ".." + endY + "]";
        }
    }

    private final List<String> tileTemplates;
    private final String version;
    private final int minZoom;
    private final int maxZoom;
    private final List<List<Range>> available;
    private final int metadataAvailability;

    private TerrainLayout(List<String> tileTemplates, String version, int minZoom, int maxZoom,
            List<List<Range>> available, int metadataAvailability) {
        this.tileTemplates = tileTemplates;
        this.version = version;
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.available = available;
        this.metadataAvailability = metadataAvailability;
    }

    /**
     * @throws IllegalArgumentException when the document is not a usable
     *     quantized-mesh layer (malformed, no tile template, or another projection)
     */
    public static TerrainLayout parse(String layerJson) {
        JsonObject json;
        try {
            json = Json.parseObject(layerJson);
        } catch (JsonException malformed) {
            throw new IllegalArgumentException("layer.json is not valid JSON.", malformed);
        }
        String format = json.optString("format", "quantized-mesh-1.0");
        if (!format.startsWith("quantized-mesh")) {
            throw new IllegalArgumentException("Unsupported terrain format " + format + ".");
        }
        String projection = json.optString("projection", "EPSG:4326");
        if (!"EPSG:4326".equals(projection)) {
            throw new IllegalArgumentException("Unsupported terrain projection " + projection + ".");
        }
        String scheme = json.optString("scheme", "tms");
        if (!"tms".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Unsupported terrain tiling scheme " + scheme + ".");
        }
        List<String> templates = json.optStrings("tiles");
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("layer.json lists no tile URL template.");
        }
        List<List<Range>> available = ranges(json.optArray("available"), 0);
        int maxZoom = (int) json.optLong("maxzoom", Math.max(0, available.size() - 1));
        // Availability carried in tile metadata (CesiumJS's metadataAvailability):
        // tiles at levels that are multiples of it list the next levels' tiles.
        int metadataAvailability = json.optStrings("extensions").contains("metadata")
                ? (int) json.optLong("metadataAvailability", 0) : 0;
        return new TerrainLayout(Collections.unmodifiableList(new ArrayList<>(templates)),
                json.optString("version", "1.0.0"), (int) json.optLong("minzoom", 0), maxZoom,
                Collections.unmodifiableList(available), Math.max(0, metadataAvailability));
    }

    /**
     * An {@code available} array (one list of ranges per level, starting at
     * {@code firstLevel}), as in {@code layer.json} and the tile metadata extension.
     */
    static List<List<Range>> ranges(List<Object> levels, int firstLevel) {
        List<List<Range>> available = new ArrayList<>();
        int level = firstLevel;
        for (Object levelRanges : levels) {
            List<Range> ranges = new ArrayList<>();
            if (levelRanges instanceof List) {
                for (Object item : (List<?>) levelRanges) {
                    if (item instanceof java.util.Map) {
                        JsonObject range = JsonObject.wrap(item);
                        ranges.add(new Range(level, (int) range.optLong("startX", 0), (int) range.optLong("startY", 0),
                                (int) range.optLong("endX", -1), (int) range.optLong("endY", -1)));
                    }
                }
            }
            available.add(Collections.unmodifiableList(ranges));
            level++;
        }
        return available;
    }

    public int minZoom() {
        return minZoom;
    }

    public int maxZoom() {
        return maxZoom;
    }

    /**
     * How many levels a tile's metadata lists availability for (tiles at
     * multiples of it carry the metadata), or 0 when availability comes from
     * {@code layer.json} alone.
     */
    public int metadataAvailability() {
        return metadataAvailability;
    }

    /** The number of levels {@code layer.json} lists availability for. */
    public int listedLevels() {
        return available.size();
    }

    /**
     * The tiles listed as available at {@code level}. Levels that
     * {@code layer.json} does not list (deeper levels whose availability is
     * carried in tile metadata) report the whole level.
     */
    public List<Range> available(int level) {
        if (level < 0 || level > maxZoom) {
            return Collections.emptyList();
        }
        if (level < available.size()) {
            return available.get(level);
        }
        return Collections.singletonList(new Range(level, 0, 0, (2 << level) - 1, (1 << level) - 1));
    }

    /** Whether {@code layer.json} says the tile exists (always true for unlisted levels in range). */
    public boolean isAvailable(int level, int x, int y) {
        for (Range range : available(level)) {
            if (range.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The tile's absolute URL, resolved against {@code baseUrl} (the
     * directory holding {@code layer.json}), without credentials.
     */
    public String tileUrl(String baseUrl, int level, int x, int y) {
        String path = tileTemplates.get(0)
                .replace("{z}", Integer.toString(level))
                .replace("{x}", Integer.toString(x))
                .replace("{y}", Integer.toString(y))
                .replace("{version}", version);
        return TileUrls.resolve(baseUrl, path);
    }
}
