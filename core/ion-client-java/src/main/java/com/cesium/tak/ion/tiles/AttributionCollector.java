package com.cesium.tak.ion.tiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates per-tile copyright strings into the credit line Google
 * Photorealistic 3D Tiles require: entries ordered by how many tiles cite
 * them, most frequent first.
 *
 * <p>Platforms record the tiles currently displayed (or, when the renderer
 * cannot report visibility, the tiles recently streamed) and periodically
 * {@link #reset()}. Thread-safe.
 */
public final class AttributionCollector {
    private final Map<String, Integer> counts = new HashMap<>();
    private final Map<String, Integer> firstSeen = new LinkedHashMap<>();

    /** Records the copyright entries of one tile. */
    public synchronized void record(List<String> copyrightEntries) {
        for (String entry : copyrightEntries) {
            Integer count = counts.get(entry);
            counts.put(entry, count == null ? 1 : count + 1);
            if (!firstSeen.containsKey(entry)) {
                firstSeen.put(entry, firstSeen.size());
            }
        }
    }

    /** Records a tile from its raw bytes; content without a glTF copyright is ignored. */
    public void recordTile(byte[] content) {
        TileContent.GltfInfo info = TileContent.gltfInfo(content);
        if (info != null) {
            record(info.copyrightEntries());
        }
    }

    /** Entries by descending frequency; ties keep first-seen order. */
    public synchronized List<String> entries() {
        List<String> entries = new ArrayList<>(counts.keySet());
        Collections.sort(entries, (left, right) -> {
            int byCount = Integer.compare(counts.get(right), counts.get(left));
            return byCount != 0 ? byCount : Integer.compare(firstSeen.get(left), firstSeen.get(right));
        });
        return entries;
    }

    /** The credit line, e.g. {@code "Google; Landsat / Copernicus"}. */
    public String creditLine() {
        StringBuilder line = new StringBuilder();
        for (String entry : entries()) {
            if (line.length() > 0) {
                line.append("; ");
            }
            line.append(entry);
        }
        return line.toString();
    }

    public synchronized void reset() {
        counts.clear();
        firstSeen.clear();
    }
}
