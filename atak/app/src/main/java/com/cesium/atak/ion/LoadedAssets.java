package com.cesium.atak.ion;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The ion assets this plugin has handed to ATAK, so they can be removed
 * later and re-registered with the tile proxy when ATAK restarts (ATAK
 * reloads the imported layers itself). No credentials are stored here.
 */
final class LoadedAssets {
    /** How an asset was handed to ATAK. */
    enum Kind {
        /** A Streaming Mesh Configuration (3D Tiles). */
        TILES_3D,
        /** A mobile map source XML (raster imagery). */
        IMAGERY,
        /** A downloaded GeoJSON, KML, or KMZ file. */
        VECTOR,
        /** Quantized-mesh terrain attached to ATAK's elevation; {@code file} is its tile cache. */
        TERRAIN
    }

    /** One loaded asset. */
    static final class Entry {
        final long assetId;
        final Kind kind;
        final String name;
        final String file;
        final boolean proxied;

        Entry(long assetId, Kind kind, String name, String file, boolean proxied) {
            this.assetId = assetId;
            this.kind = kind;
            this.name = name;
            this.file = file;
            this.proxied = proxied;
        }
    }

    private static final String PREFERENCES = "cesium_ion_loaded";
    private static final String KEY_IDS = "ids";

    private final SharedPreferences preferences;

    LoadedAssets(Context hostContext) {
        preferences = hostContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    synchronized void put(Entry entry) {
        Set<String> ids = new HashSet<>(preferences.getStringSet(KEY_IDS, Collections.<String>emptySet()));
        ids.add(Long.toString(entry.assetId));
        String prefix = entry.assetId + ".";
        preferences.edit()
                .putStringSet(KEY_IDS, ids)
                .putString(prefix + "kind", entry.kind.name())
                .putString(prefix + "name", entry.name)
                .putString(prefix + "file", entry.file)
                .putBoolean(prefix + "proxied", entry.proxied)
                .apply();
    }

    synchronized Entry get(long assetId) {
        if (!preferences.getStringSet(KEY_IDS, Collections.<String>emptySet()).contains(Long.toString(assetId))) {
            return null;
        }
        String prefix = assetId + ".";
        Kind kind;
        try {
            kind = Kind.valueOf(preferences.getString(prefix + "kind", ""));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        return new Entry(assetId, kind, preferences.getString(prefix + "name", "Asset " + assetId),
                preferences.getString(prefix + "file", ""), preferences.getBoolean(prefix + "proxied", false));
    }

    synchronized List<Entry> all() {
        List<Entry> entries = new ArrayList<>();
        for (String id : preferences.getStringSet(KEY_IDS, Collections.<String>emptySet())) {
            try {
                Entry entry = get(Long.parseLong(id));
                if (entry != null) {
                    entries.add(entry);
                }
            } catch (NumberFormatException ignored) {
                // Not written by this class.
            }
        }
        return entries;
    }

    synchronized void remove(long assetId) {
        Set<String> ids = new HashSet<>(preferences.getStringSet(KEY_IDS, Collections.<String>emptySet()));
        ids.remove(Long.toString(assetId));
        String prefix = assetId + ".";
        preferences.edit()
                .putStringSet(KEY_IDS, ids)
                .remove(prefix + "kind")
                .remove(prefix + "name")
                .remove(prefix + "file")
                .remove(prefix + "proxied")
                .apply();
    }
}
