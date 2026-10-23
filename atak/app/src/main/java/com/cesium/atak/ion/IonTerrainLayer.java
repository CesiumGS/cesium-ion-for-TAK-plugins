package com.cesium.atak.ion;

import com.atakmap.map.formats.quantizedmesh.QMESourceLayer;
import com.atakmap.map.formats.quantizedmesh.TileCoord;
import com.atakmap.map.formats.quantizedmesh.TileExtents;
import com.cesium.tak.ion.IonException;
import com.cesium.tak.ion.http.HttpRequest;
import com.cesium.tak.ion.http.HttpResponse;
import com.cesium.tak.ion.http.HttpTransport;
import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.terrain.QuantizedMesh;
import com.cesium.tak.ion.terrain.TerrainLayout;
import com.cesium.tak.ion.terrain.TileAvailability;
import com.cesium.tak.ion.tiles.TileUrls;
import com.cesium.tak.ion.util.Redactor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cesium ion quantized-mesh terrain as an ATAK {@link QMESourceLayer}: a
 * directory of cached tiles that ATAK's terrain engine reads, filled on
 * demand ({@link #startDataRequest}) from ion with the endpoint token, which
 * never reaches the files.
 *
 * <p>ATAK calls in from its own threads with (x, y, level), confirmed on
 * device. ATAK 5.7 reads only tiles already on disk: it asks for the tile at
 * {@link #getClosestLevel} and walks down the levels until it finds a cached
 * file, and does not call {@link #startDataRequest} itself. So every tile it
 * asks about and does not find is fetched in the background; it is used the
 * next time ATAK queries that area (when the map moves).
 *
 * <p>Only tiles known to exist are requested. Terrains whose
 * {@code layer.json} lists no availability (Cesium ion Self-Hosted) carry it
 * in tile metadata, as CesiumJS reads it: tiles at every
 * {@code metadataAvailability}-th level list the tiles below them, so those
 * are fetched first, with the metadata extension, which is saved beside the
 * cached tile and stripped from it. Requesting tiles that do not exist
 * crashes Cesium ion Self-Hosted 1.10.0's asset server.
 */
final class IonTerrainLayer implements QMESourceLayer {
    private static final String ACCEPT = "application/vnd.quantized-mesh,application/octet-stream;q=0.9,*/*;q=0.01";
    private static final String METADATA_ACCEPT =
            "application/vnd.quantized-mesh;extensions=metadata,application/octet-stream;q=0.9,*/*;q=0.01";
    private static final String AVAILABILITY_SUFFIX = ".availability.json";
    private static final int LOGGED_CALLS = 8;
    /** Tiles queued at once; requests beyond this wait for ATAK to ask again. */
    private static final int MAX_PENDING = 256;

    private final long assetId;
    private final File directory;
    private final IonSession session;
    private final HttpTransport transport;
    private final ExecutorService downloads;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Set<String> missing = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> totals = new ConcurrentHashMap<>();
    private final AtomicInteger downloaded = new AtomicInteger();
    private final AtomicInteger unavailable = new AtomicInteger();
    /** Tiles on disk, so ATAK's frequent hasTile calls need no file system access. */
    private final Set<String> cached = ConcurrentHashMap.newKeySet();
    /**
     * Built once per layer: ATAK asks for these on its main thread for every
     * terrain query, and Cesium World Terrain lists ~11,000 ranges at some
     * levels (each read by ATAK through four JNI calls). More than
     * {@link #MAX_EXTENTS_PER_LEVEL} ranges collapse to their bounding range;
     * tiles inside it that do not exist are 404s, remembered as missing.
     */
    private volatile List<List<TileExtents>> extentsByLevel = new ArrayList<>();
    private volatile double[] spacingByLevel = new double[0];
    private static final int MAX_EXTENTS_PER_LEVEL = 16;
    private volatile TerrainLayout layout;
    private volatile TileAvailability availability;
    private volatile String baseUrl;
    private volatile boolean enabled = true;
    private volatile int cachedAtAttach;
    private volatile long attachedAtMillis;

    IonTerrainLayer(long assetId, File directory, IonSession session, HttpTransport transport,
            ExecutorService downloads) {
        this.assetId = assetId;
        this.directory = directory;
        this.session = session;
        this.transport = transport;
        this.downloads = downloads;
    }

    /** Reads {@code layer.json}. Call off the UI thread, before attaching. */
    TerrainLayout open() throws IonException, IOException {
        AssetEndpoint endpoint = session.endpoint(assetId);
        String base = directoryOf(endpoint.url());
        HttpResponse response = get(endpoint.authorize(TileUrls.deriveChild(base, "layer.json")), "application/json");
        if (!response.isSuccessful()) {
            throw new IOException("layer.json for asset " + assetId + " returned HTTP " + response.status() + ".");
        }
        try {
            layout = TerrainLayout.parse(response.bodyText());
        } catch (IllegalArgumentException unusable) {
            throw new IOException("Asset " + assetId + ": " + unusable.getMessage(), unusable);
        }
        baseUrl = TileUrls.withoutQueryParameters(base, endpoint.credentials().keySet());
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create " + directory);
        }
        availability = new TileAvailability(layout);
        prepareExtents(layout);
        indexCache();
        return layout;
    }

    private void prepareExtents(TerrainLayout current) {
        List<List<TileExtents>> byLevel = new ArrayList<>();
        double[] spacing = new double[current.maxZoom() + 1];
        for (int level = 0; level <= current.maxZoom(); level++) {
            List<TerrainLayout.Range> ranges = current.available(level);
            List<TileExtents> extents = new ArrayList<>();
            if (ranges.size() <= MAX_EXTENTS_PER_LEVEL) {
                for (TerrainLayout.Range range : ranges) {
                    extents.add(new Extents(range));
                }
            } else {
                int minX = Integer.MAX_VALUE;
                int minY = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                int maxY = Integer.MIN_VALUE;
                for (TerrainLayout.Range range : ranges) {
                    minX = Math.min(minX, range.startX);
                    minY = Math.min(minY, range.startY);
                    maxX = Math.max(maxX, range.endX);
                    maxY = Math.max(maxY, range.endY);
                }
                extents.add(new Extents(new TerrainLayout.Range(level, minX, minY, maxX, maxY)));
            }
            byLevel.add(Collections.unmodifiableList(extents));
            spacing[level] = levelSpacing(level);
        }
        extentsByLevel = byLevel;
        spacingByLevel = spacing;
    }

    /** Records the tiles already on disk (level/x/y.terrain) and the availability saved beside them. */
    private void indexCache() {
        cached.clear();
        TileAvailability known = availability;
        File[] levels = directory.listFiles(File::isDirectory);
        for (File levelDir : levels == null ? new File[0] : levels) {
            File[] columns = levelDir.listFiles(File::isDirectory);
            for (File column : columns == null ? new File[0] : columns) {
                String[] rows = column.list((dir, name) -> name.endsWith(".terrain"));
                for (String row : rows == null ? new String[0] : rows) {
                    cached.add(levelDir.getName() + "/" + column.getName() + "/"
                            + row.substring(0, row.length() - ".terrain".length()));
                }
                String[] saved = column.list((dir, name) -> name.endsWith(AVAILABILITY_SUFFIX));
                for (String name : saved == null ? new String[0] : saved) {
                    try {
                        int level = Integer.parseInt(levelDir.getName());
                        int x = Integer.parseInt(column.getName());
                        int y = Integer.parseInt(name.substring(0, name.length() - AVAILABILITY_SUFFIX.length()));
                        String json = new String(Files.readAllBytes(new File(column, name).toPath()),
                                StandardCharsets.UTF_8);
                        known.addMetadata(level, x, y, QuantizedMesh.availability(json, level));
                    } catch (IOException | NumberFormatException unreadable) {
                        PluginLog.w("Terrain #" + assetId + ": ignoring " + name + " (" + unreadable + ").");
                    }
                }
            }
        }
    }

    private static String key(int x, int y, int level) {
        return level + "/" + x + "/" + y;
    }

    /** ATAK's calls into this layer since the last call to this method, e.g. {@code hasTile=1200}. */
    String drainCallCounts() {
        StringBuilder counts = new StringBuilder();
        for (Map.Entry<String, AtomicLong> entry : totals.entrySet()) {
            long count = entry.getValue().getAndSet(0);
            if (count > 0) {
                counts.append(counts.length() == 0 ? "" : ", ").append(entry.getKey()).append('=').append(count);
            }
        }
        return counts.toString();
    }

    /** Records that ATAK (re)read the layer now, with the tiles cached so far. */
    void markAttached() {
        cachedAtAttach = downloaded.get();
        attachedAtMillis = System.currentTimeMillis();
    }

    /** Tiles cached since ATAK last attached the layer. */
    int newTilesSinceAttach() {
        return downloaded.get() - cachedAtAttach;
    }

    long attachedAtMillis() {
        return attachedAtMillis;
    }

    int cachedCount() {
        return downloaded.get();
    }

    long assetId() {
        return assetId;
    }

    /**
     * Queues the tiles around a point: a (2r+1)-square at {@code level} and
     * the tile under the point at each coarser level.
     */
    void prefetch(double latitude, double longitude, int level, int radius) {
        TerrainLayout current = layout;
        if (current == null) {
            return;
        }
        int top = Math.min(level, current.maxZoom());
        for (int z = top; z >= Math.max(current.minZoom(), top - 6); z--) {
            double size = 180.0 / (1 << z);
            int cx = (int) Math.floor((longitude + 180.0) / size);
            int cy = (int) Math.floor((latitude + 90.0) / size);
            int r = z == top ? radius : 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    if (x >= 0 && x < (2 << z) && y >= 0 && y < (1 << z)) {
                        fetch(x, y, z);
                    }
                }
            }
        }
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Deletes the cached tiles. */
    void deleteCache() {
        cached.clear();
        deleteRecursively(directory);
    }

    @Override
    public int getMinZoom() {
        return layout == null ? 0 : layout.minZoom();
    }

    @Override
    public int getMaxZoom() {
        return layout == null ? 0 : layout.maxZoom();
    }

    @Override
    public int getMaxLevel() {
        return getMaxZoom();
    }

    @Override
    public boolean isLocalDirectoryValid() {
        return directory.isDirectory();
    }

    /** Picks the level whose tile spacing (as ATAK's own TileCoord measures it) is closest. */
    @Override
    public int getClosestLevel(double spacing) {
        double[] spacings = spacingByLevel;
        int best = getMinZoom();
        double bestDifference = Double.MAX_VALUE;
        for (int level = getMinZoom(); level < spacings.length; level++) {
            double difference = Math.abs(spacings[level] - spacing);
            if (difference < bestDifference) {
                bestDifference = difference;
                best = level;
            }
        }
        if (shouldLog("getClosestLevel")) {
            PluginLog.i("Terrain #" + assetId + " getClosestLevel("
                    + String.format(Locale.US, "%.6g -> %d", spacing, best) + ")");
        }
        return best;
    }

    private static double levelSpacing(int level) {
        try {
            return TileCoord.getSpacing(level);
        } catch (RuntimeException | LinkageError unavailable) {
            return 180.0 / (1 << level);
        }
    }

    @Override
    public File getDirectory() {
        return directory;
    }

    @Override
    public File getLevelDirectory(int level) {
        return new File(directory, Integer.toString(level));
    }

    @Override
    public File getTileFile(int x, int y, int level) {
        if (shouldLog("getTileFile")) {
            PluginLog.i("Terrain #" + assetId + " getTileFile(" + x + "," + y + "," + level + ")");
        }
        if (onMainThread()) {
            return new File(directory, "none.terrain"); // see onMainThread()
        }
        if (!cached.contains(key(x, y, level))) {
            fetch(x, y, level);
        }
        return tileFile(x, y, level);
    }

    /**
     * ATAK queries elevation on its main thread whenever a map item is added
     * (opening any pane adds one). With a quantized-mesh source, that query
     * stitches tile seams in ATAK's engine under a native lock, and on
     * 2026-10-21 it hung there for good while terrain was loading (ANR,
     * "ATAK froze"; nothing of ours on any stack). So the main thread gets no
     * tiles from this layer: its point queries fall back to ATAK's other
     * elevation sources, while the 3D map (drawn on other threads) still uses
     * this terrain.
     */
    private static boolean onMainThread() {
        try {
            android.os.Looper main = android.os.Looper.getMainLooper();
            return main != null && main.isCurrentThread();
        } catch (RuntimeException notOnAndroid) {
            return false;
        }
    }

    private File tileFile(int x, int y, int level) {
        return new File(new File(getLevelDirectory(level), Integer.toString(x)), y + ".terrain");
    }

    /** The metadata extension's JSON, kept beside a metadata tile. */
    private File availabilityFile(int x, int y, int level) {
        return new File(new File(getLevelDirectory(level), Integer.toString(x)), y + AVAILABILITY_SUFFIX);
    }

    @Override
    public boolean isValid() {
        return layout != null;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean hasTile(int x, int y, int level) {
        if (onMainThread()) {
            return false; // see onMainThread()
        }
        boolean has = cached.contains(key(x, y, level));
        if (shouldLog("hasTile")) {
            PluginLog.i("Terrain #" + assetId + " hasTile(" + x + "," + y + "," + level + " -> " + has + ")");
        }
        if (!has) {
            fetch(x, y, level);
        }
        return has;
    }

    @Override
    public List<TileExtents> getAvailableExtents(int level) {
        List<List<TileExtents>> byLevel = extentsByLevel;
        List<TileExtents> extents = level >= 0 && level < byLevel.size()
                ? byLevel.get(level) : Collections.<TileExtents>emptyList();
        if (shouldLog("getAvailableExtents")) {
            PluginLog.i("Terrain #" + assetId + " getAvailableExtents(" + level + " -> " + extents.size() + " ranges)");
        }
        return extents;
    }

    @Override
    public void startDataRequest(int x, int y, int level) {
        if (shouldLog("startDataRequest")) {
            PluginLog.i("Terrain #" + assetId + " startDataRequest(" + x + "," + y + "," + level + ")");
        }
        fetch(x, y, level);
    }

    /** Queues a background download unless the tile is cached, known missing, queued, or unlisted. */
    private void fetch(int x, int y, int level) {
        // Runs on ATAK's threads (often the main one): set lookups only.
        TerrainLayout current = layout;
        String key = key(x, y, level);
        if (current == null || !enabled || cached.contains(key) || missing.contains(key)
                || pending.size() >= MAX_PENDING || !pending.add(key)) {
            return;
        }
        try {
            downloads.execute(() -> {
                try {
                    download(current, x, y, level, key);
                } finally {
                    pending.remove(key);
                }
            });
        } catch (RejectedExecutionException stopping) {
            pending.remove(key);
        }
    }

    private void download(TerrainLayout current, int x, int y, int level, String key) {
        if (!enabled) {
            return; // removed (or the server changed) while this download was queued
        }
        TileAvailability known = availability;
        switch (known.status(level, x, y)) {
            case UNAVAILABLE:
                missing.add(key);
                return;
            case UNKNOWN:
                // Learn from the metadata tile first; ATAK asks for this tile again later.
                learnAvailability(current, known, level, x, y);
                return;
            default:
                break;
        }
        boolean metadataTile = known.carriesMetadata(level);
        if (tileFile(x, y, level).isFile() && (!metadataTile || availabilityFile(x, y, level).isFile())) {
            cached.add(key);
            return;
        }
        fetchTile(current, known, x, y, level, key);
    }

    /**
     * Fetches the nearest metadata ancestor of a tile whose availability is
     * unknown, walking up while those are unknown too (a level-20 tile needs
     * its level-10 ancestor, which needs its level-0 one).
     */
    private void learnAvailability(TerrainLayout current, TileAvailability known, int level, int x, int y) {
        int[] parent = known.metadataParent(level, x, y);
        while (parent[0] > 0 && known.status(parent[0], parent[1], parent[2]) == TileAvailability.Status.UNKNOWN) {
            parent = known.metadataParent(parent[0], parent[1], parent[2]);
        }
        if (known.status(parent[0], parent[1], parent[2]) != TileAvailability.Status.AVAILABLE) {
            return;
        }
        if (missing.contains(key(parent[1], parent[2], parent[0]))) {
            return; // the metadata tile itself 404'd: nothing below it is requested
        }
        String parentKey = "metadata:" + key(parent[1], parent[2], parent[0]);
        if (!pending.add(parentKey)) {
            return;
        }
        try {
            fetchTile(current, known, parent[1], parent[2], parent[0], key(parent[1], parent[2], parent[0]));
        } finally {
            pending.remove(parentKey);
        }
    }

    private void fetchTile(TerrainLayout current, TileAvailability known, int x, int y, int level, String key) {
        boolean metadataTile = known.carriesMetadata(level);
        for (int attempt = 0; attempt < 2; attempt++) {
            AssetEndpoint endpoint;
            try {
                endpoint = session.endpoint(assetId);
            } catch (IonException failure) {
                PluginLog.w("Terrain #" + assetId + " endpoint unavailable: " + failure.getMessage());
                return;
            }
            String url = endpoint.authorize(current.tileUrl(baseUrl, level, x, y));
            HttpResponse response;
            try {
                response = get(url, metadataTile ? METADATA_ACCEPT : ACCEPT);
            } catch (IOException failure) {
                PluginLog.w("Terrain #" + assetId + " tile " + key + " failed: "
                        + Redactor.redact(String.valueOf(failure.getMessage())));
                return;
            }
            int status = response.status();
            if ((status == 401 || status == 403) && attempt == 0) {
                session.invalidate(assetId, endpoint);
                continue;
            }
            if (status == 404 || status == 204) {
                missing.add(key);
                int count = unavailable.incrementAndGet();
                if (count == 1 || count % 100 == 0) {
                    PluginLog.i("Terrain #" + assetId + ": " + count + " tiles do not exist at the requested level"
                            + " (latest " + key + "); ATAK uses a coarser level there.");
                }
                return;
            }
            if (!response.isSuccessful()) {
                PluginLog.w("Terrain #" + assetId + " tile " + key + " returned HTTP " + status + ".");
                return;
            }
            byte[] body = response.body();
            try {
                if (metadataTile) {
                    String metadata = QuantizedMesh.metadataJson(body);
                    if (metadata != null) {
                        write(availabilityFile(x, y, level), metadata.getBytes(StandardCharsets.UTF_8));
                        known.addMetadata(level, x, y, QuantizedMesh.availability(metadata, level));
                    } else {
                        PluginLog.w("Terrain #" + assetId + " tile " + key + " has no availability metadata.");
                    }
                    // ATAK reads plain quantized-mesh tiles.
                    body = QuantizedMesh.withoutExtensions(body);
                }
                write(tileFile(x, y, level), body);
                cached.add(key);
            } catch (IOException failure) {
                PluginLog.w("Terrain #" + assetId + " could not cache " + key + ": " + failure.getMessage());
                return;
            }
            int count = downloaded.incrementAndGet();
            if (count <= 3 || count % 100 == 0) {
                PluginLog.i("Terrain #" + assetId + ": " + count + " tiles cached (latest " + key + ", "
                        + body.length + " bytes).");
            }
            return;
        }
    }

    private HttpResponse get(String url, String accept) throws IOException {
        try {
            return transport.send(HttpRequest.get(URI.create(url)).header("Accept", accept).build());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid terrain URL.", invalid);
        }
    }

    /** Writes atomically so ATAK never reads a partial tile. */
    private static void write(File file, byte[] body) throws IOException {
        File folder = file.getParentFile();
        // Another download thread may create the same folder at the same moment.
        if (folder != null && !folder.mkdirs() && !folder.isDirectory()) {
            throw new IOException("Could not create " + folder);
        }
        File partial = new File(file.getPath() + ".part");
        try (FileOutputStream output = new FileOutputStream(partial)) {
            output.write(body);
        }
        if (!partial.renameTo(file)) {
            partial.delete();
            throw new IOException("Could not move " + partial.getName() + " into place.");
        }
    }

    /** Whether to log this call: the first few of each method only, without building strings otherwise. */
    private boolean shouldLog(String method) {
        AtomicLong total = totals.get(method);
        if (total == null) {
            total = totals.computeIfAbsent(method, name -> new AtomicLong());
        }
        total.incrementAndGet();
        AtomicInteger count = calls.get(method);
        if (count == null) {
            count = calls.computeIfAbsent(method, name -> new AtomicInteger());
        }
        return count.get() < LOGGED_CALLS && count.incrementAndGet() <= LOGGED_CALLS;
    }

    private static String directoryOf(String url) {
        String path = TileUrls.path(url);
        if (path.endsWith("/")) {
            return url;
        }
        if (path.endsWith("layer.json")) {
            return TileUrls.resolve(url, "./");
        }
        // ion terrain endpoints name the directory without a trailing slash.
        int query = url.indexOf('?');
        return query < 0 ? url + "/" : url.substring(0, query) + "/" + url.substring(query);
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (file.exists() && !file.delete()) {
            PluginLog.w("Could not delete " + file);
        }
    }

    private static final class Extents implements TileExtents {
        private final TerrainLayout.Range range;

        Extents(TerrainLayout.Range range) {
            this.range = range;
        }

        @Override
        public int getStartX() {
            return range.startX;
        }

        @Override
        public int getStartY() {
            return range.startY;
        }

        @Override
        public int getEndX() {
            return range.endX;
        }

        @Override
        public int getEndY() {
            return range.endY;
        }

        @Override
        public int getLevel() {
            return range.level;
        }
    }
}
