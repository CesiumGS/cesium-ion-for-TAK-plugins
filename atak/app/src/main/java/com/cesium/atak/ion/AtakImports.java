package com.cesium.atak.ion;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;

import com.atakmap.android.importexport.ImportExportMapComponent;
import com.atakmap.android.importexport.ImportReceiver;
import com.atakmap.android.ipc.AtakBroadcast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** Hands content to ATAK's import pipeline. */
final class AtakImports {
    /** {@code LayersMapComponent.IMPORTER_CONTENT_TYPE} in ATAK 5.7 (a compile-time constant). */
    private static final String MAP_SOURCE_CONTENT_TYPE = "External Native Data";
    /** What ATAK itself sends when importing its bundled map sources. */
    private static final String MAP_SOURCE_MIME_TYPE = "application/xml";
    private static final String SHARED_MAP_SOURCES_DIR = "imagery/mobile/mapsources";
    /** {@code GeoJSONSpatialDb.GEOJSON_CONTENT_TYPE} and its file MIME type in ATAK 5.7. */
    static final String GEOJSON_CONTENT_TYPE = "GeoJSON";
    private static final String GEOJSON_MIME_TYPE = "application/octet-stream";
    /** {@code KmlFileSpatialDb.KML_CONTENT_TYPE} and its MIME types in ATAK 5.7. */
    static final String KML_CONTENT_TYPE = "KML";
    private static final String KML_MIME_TYPE = "application/vnd.google-earth.kml+xml";
    private static final String KMZ_MIME_TYPE = "application/vnd.google-earth.kmz";

    private AtakImports() {
    }

    /**
     * Writes a Streaming Mesh Configuration for a 3D Tiles tileset into
     * {@code directory} and asks ATAK to import it.
     *
     * @return the configuration file, which identifies the layer for removal
     */
    static File importTileset(File directory, long assetId, String name, String tilesetUrl) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        File config = new File(directory, StreamingMeshConfig.fileName(assetId));
        try (FileOutputStream output = new FileOutputStream(config)) {
            output.write(StreamingMeshConfig.json(tilesetUrl, name).getBytes(StandardCharsets.UTF_8));
        }
        Bundle extras = new Bundle();
        extras.putBoolean("streaming", true);
        extras.putString("datasetName", name);
        broadcast(ImportExportMapComponent.ACTION_IMPORT_DATA, config.getAbsolutePath(),
                StreamingMeshConfig.CONTENT_TYPE, StreamingMeshConfig.MIME_TYPE, extras);
        return config;
    }

    /**
     * Asks ATAK to remove a layer imported by {@link #importTileset}, then
     * deletes its file. ATAK turns the configuration into a "3D Model" layer
     * keyed by the tileset URI and reloads that layer at every start, so it
     * is deleted too: otherwise removed tilesets are requested again at each
     * start (and an unreachable one stalls ATAK's map for minutes).
     */
    static void removeTileset(File config) {
        String tilesetUri = null;
        if (config.isFile()) {
            try {
                byte[] bytes = new byte[(int) Math.min(config.length(), 64 * 1024)];
                try (FileInputStream input = new FileInputStream(config)) {
                    int read = 0;
                    while (read < bytes.length) {
                        int n = input.read(bytes, read, bytes.length - read);
                        if (n < 0) {
                            break;
                        }
                        read += n;
                    }
                    tilesetUri = StreamingMeshConfig.tilesetUri(new String(bytes, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException unreadable) {
                PluginLog.w("Could not read " + config + ": " + unreadable.getMessage());
            }
        }
        if (tilesetUri != null) {
            broadcast(ImportExportMapComponent.ACTION_DELETE_DATA, tilesetUri,
                    StreamingMeshConfig.MODEL_CONTENT_TYPE, null, null);
        }
        broadcast(ImportExportMapComponent.ACTION_DELETE_DATA, config.getAbsolutePath(),
                StreamingMeshConfig.CONTENT_TYPE, StreamingMeshConfig.MIME_TYPE, null);
        if (config.exists() && !config.delete()) {
            PluginLog.w("Could not delete " + config);
        }
    }

    /**
     * Writes a MOBAC map source into {@code directory} (ATAK's private
     * storage) and asks ATAK to load it, as ATAK does for its own bundled map
     * sources. The XML holds no credentials: only direct tile URLs or
     * loopback proxy URLs.
     *
     * @return the map-source file, which identifies the layer for removal
     */
    static File importMapSource(File directory, long assetId, String xml) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        File source = new File(directory, MapSourceXml.fileName(assetId));
        try (FileOutputStream output = new FileOutputStream(source)) {
            output.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        broadcast(ImportExportMapComponent.ACTION_IMPORT_DATA, source.getAbsolutePath(), MAP_SOURCE_CONTENT_TYPE,
                MAP_SOURCE_MIME_TYPE, null);
        return source;
    }

    /** Removes a map source written by {@link #importMapSource} (or an earlier build). */
    static void removeMapSource(File source) {
        broadcast(ImportExportMapComponent.ACTION_DELETE_DATA, source.getAbsolutePath(), MAP_SOURCE_CONTENT_TYPE,
                MAP_SOURCE_MIME_TYPE, null);
        if (source.exists() && !source.delete()) {
            PluginLog.w("Could not delete " + source);
        }
    }

    /**
     * Asks ATAK to import a downloaded GeoJSON, KML, or KMZ file in place;
     * ATAK lists it in Overlay Manager under the file's name.
     */
    static void importVector(File file) {
        broadcast(ImportExportMapComponent.ACTION_IMPORT_DATA, file.getAbsolutePath(), vectorContentType(file),
                vectorMimeType(file), null);
    }

    /** Removes a file imported by {@link #importVector}, then deletes it. */
    static void removeVector(File file) {
        broadcast(ImportExportMapComponent.ACTION_DELETE_DATA, file.getAbsolutePath(), vectorContentType(file),
                vectorMimeType(file), null);
        if (file.exists() && !file.delete()) {
            PluginLog.w("Could not delete " + file);
        }
    }

    private static String vectorContentType(File file) {
        return file.getName().endsWith(".geojson") ? GEOJSON_CONTENT_TYPE : KML_CONTENT_TYPE;
    }

    private static String vectorMimeType(File file) {
        String name = file.getName();
        return name.endsWith(".geojson") ? GEOJSON_MIME_TYPE : name.endsWith(".kmz") ? KMZ_MIME_TYPE : KML_MIME_TYPE;
    }

    /**
     * ATAK's shared {@code <atak root>/imagery/mobile/mapsources}, where
     * earlier builds and the WebView plugin wrote map sources.
     */
    static File sharedMapSourcesDirectory() {
        try {
            Class<?> fileSystem = Class.forName("com.atakmap.coremap.filesystem.FileSystemUtils");
            Method getItem = fileSystem.getMethod("getItem", String.class);
            Object item = getItem.invoke(null, SHARED_MAP_SOURCES_DIR);
            if (item instanceof File) {
                return (File) item;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
            PluginLog.w("FileSystemUtils.getItem unavailable (" + missing + "); using the default ATAK folder.");
        }
        return new File(new File(Environment.getExternalStorageDirectory(), "atak"), SHARED_MAP_SOURCES_DIR);
    }

    private static void broadcast(String action, String uri, String contentType, String mimeType, Bundle extras) {
        Intent intent = new Intent(action);
        if (extras != null) {
            intent.putExtras(extras);
        }
        intent.putExtra(ImportReceiver.EXTRA_URI, uri);
        intent.putExtra(ImportReceiver.EXTRA_CONTENT, contentType);
        intent.putExtra(ImportReceiver.EXTRA_MIME_TYPE, mimeType);
        intent.putExtra(ImportReceiver.EXTRA_ADVANCED_OPTIONS, true);
        // Never log a query: the Google tileset URI carries its key there.
        int query = uri.indexOf('?');
        PluginLog.i("Broadcast " + action + " uri=" + (query < 0 ? uri : uri.substring(0, query))
                + " content=" + contentType);
        AtakBroadcast.getInstance().sendBroadcast(intent);
    }
}
