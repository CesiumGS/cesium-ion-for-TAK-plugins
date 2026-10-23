package com.cesium.tak.ion.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which tiles of a terrain exist, from {@code layer.json} and, when the
 * terrain uses the metadata extension, from the tiles that carry it (as
 * CesiumJS does): a tile at a level that is a multiple of
 * {@code metadataAvailability} lists the tiles below it for that many levels.
 * Tiles nobody has listed yet are {@link Status#UNKNOWN}; asking a server for
 * them wastes a request, and Cesium ion Self-Hosted 1.10.0's asset server
 * crashes on some. Thread-safe.
 */
public final class TileAvailability {
    public enum Status { AVAILABLE, UNAVAILABLE, UNKNOWN }

    private final TerrainLayout layout;
    private final int step;
    /** Ranges learned from tile metadata, by level. */
    private final Map<Integer, List<TerrainLayout.Range>> learned = new HashMap<>();
    /** Metadata tiles read so far (level/x/y). */
    private final Set<String> read = ConcurrentHashMap.newKeySet();

    public TileAvailability(TerrainLayout layout) {
        this.layout = layout;
        this.step = layout.metadataAvailability();
    }

    /** Whether this terrain carries availability in tile metadata. */
    public boolean usesMetadata() {
        return step > 0;
    }

    /** Whether the tile carries metadata for the levels below it. */
    public boolean carriesMetadata(int level) {
        return step > 0 && level % step == 0;
    }

    public Status status(int level, int x, int y) {
        if (level < layout.minZoom() || level > layout.maxZoom() || x < 0 || y < 0
                || x >= (2 << level) || y >= (1 << level)) {
            return Status.UNAVAILABLE;
        }
        if (level < layout.listedLevels() || step == 0) {
            return layout.isAvailable(level, x, y) ? Status.AVAILABLE : Status.UNAVAILABLE;
        }
        if (level == 0) {
            return Status.AVAILABLE;
        }
        synchronized (learned) {
            for (TerrainLayout.Range range : learned.getOrDefault(level, Collections.<TerrainLayout.Range>emptyList())) {
                if (range.contains(x, y)) {
                    return Status.AVAILABLE;
                }
            }
        }
        int[] parent = metadataParent(level, x, y);
        if (read.contains(parent[0] + "/" + parent[1] + "/" + parent[2])) {
            return Status.UNAVAILABLE;
        }
        // A metadata tile that does not exist lists nothing below it.
        return status(parent[0], parent[1], parent[2]) == Status.UNAVAILABLE ? Status.UNAVAILABLE : Status.UNKNOWN;
    }

    /**
     * The tile (level, x, y) whose metadata lists this one: its ancestor at
     * the deepest multiple of {@code metadataAvailability} above it.
     */
    public int[] metadataParent(int level, int x, int y) {
        int parentLevel = step == 0 ? 0 : ((level - 1) / step) * step;
        int shift = level - parentLevel;
        return new int[] {parentLevel, x >> shift, y >> shift};
    }

    /** Records the availability listed in a metadata tile's extension. */
    public void addMetadata(int level, int x, int y, List<List<TerrainLayout.Range>> ranges) {
        synchronized (learned) {
            for (List<TerrainLayout.Range> levelRanges : ranges) {
                for (TerrainLayout.Range range : levelRanges) {
                    learned.computeIfAbsent(range.level, l -> new ArrayList<>()).add(range);
                }
            }
        }
        read.add(level + "/" + x + "/" + y);
    }
}
