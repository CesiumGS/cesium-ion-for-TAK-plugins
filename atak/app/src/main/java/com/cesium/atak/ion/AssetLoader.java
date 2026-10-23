package com.cesium.atak.ion;

import com.atakmap.map.formats.quantizedmesh.QMESourceManager;
import com.cesium.tak.ion.IonException;
import com.cesium.tak.ion.http.HttpRequest;
import com.cesium.tak.ion.http.HttpResponse;
import com.cesium.tak.ion.http.HttpTransport;
import com.cesium.tak.ion.imagery.ImageryResolver;
import com.cesium.tak.ion.imagery.TileTemplate;
import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.model.Attribution;
import com.cesium.tak.ion.terrain.TerrainLayout;
import com.cesium.tak.ion.tiles.TilesetBounds;
import com.cesium.tak.ion.util.Redactor;
import com.cesium.tak.ion.vector.GeoJsonBounds;
import com.cesium.tak.ion.vector.GeoJsonToKml;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Hands ion assets to ATAK: 3D Tiles as Streaming Mesh Configurations and
 * raster imagery as mobile map sources. Content that needs credentials goes
 * through the loopback {@link ProxyHost}, so ATAK's files hold no tokens;
 * that includes Google Photorealistic 3D Tiles and their Google key.
 *
 * <p>Call everything except the constructor off the UI thread.
 */
final class AssetLoader {
    /** The outcome of {@link #load}, for the pane to adjust the map. */
    static final class Result {
        final LoadedAssets.Entry entry;
        final boolean photorealistic;
        /** Where to look, or {@code null} when unknown or global. */
        final TilesetBounds bounds;

        Result(LoadedAssets.Entry entry, boolean photorealistic, TilesetBounds bounds) {
            this.entry = entry;
            this.photorealistic = photorealistic;
            this.bounds = bounds;
        }
    }

    /** Tilesets wider than this are global: there is nowhere useful to fly to. */
    private static final double MAX_FLY_TO_RADIUS_METRES = 2_000_000;
    /** How high GeoJSON floats above the ground (so 3D Tiles do not hide it). */
    static final double VECTOR_ALTITUDE_METRES = 2;
    /** Where ion serves the Google and Cesium credit logos. */
    private static final String CREDIT_IMAGE_HOST = "assets.ion.cesium.com";
    private static final int MAX_CREDIT_IMAGE_BYTES = 256 * 1024;
    private final Map<Long, File> creditLogos = new ConcurrentHashMap<>();

    private final File streamingDirectory;
    private final File mapSourcesDirectory;
    private final File vectorsDirectory;
    private final File terrainDirectory;
    private final File creditsDirectory;
    /** Attached terrain layers; ATAK holds them until detached. */
    private final Map<Long, IonTerrainLayer> terrainLayers = new ConcurrentHashMap<>();
    private final ExecutorService terrainDownloads = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "cesium-ion-terrain");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * ATAK reads which terrain tiles exist when a layer is attached, so the
     * refresher prefetches around the camera and re-attaches when new tiles
     * have arrived.
     */
    private final ScheduledExecutorService terrainRefresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cesium-ion-terrain-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private static final long REFRESH_SECONDS = 3;
    /**
     * Re-attaching makes ATAK rebuild its terrain source, and while it does,
     * ATAK's own elevation queries on its main thread wait for the terrain
     * locks (ANR in ATAK-CIV 5.7.0.16 on 2026-10-21: opening the pane right
     * after a restart, with terrain attached). So keep it rare: enough new
     * tiles, a minute since the last one, and only while the map is still.
     */
    private static final long MIN_REATTACH_MILLIS = 60_000;
    private static final int MIN_NEW_TILES = 24;
    /** Refresh ticks (REFRESH_SECONDS each) the camera must be still before a re-attach. */
    private static final int STILL_TICKS = 2;
    /** Terrain from the last session is attached this long after the plugin starts, when ATAK has settled. */
    private static final long STARTUP_TERRAIN_DELAY_SECONDS = 30;
    /**
     * ATAK runs terrain queries on its main thread (e.g. while the camera
     * animates) and they call back into the layer; attaching or detaching
     * from another thread at the same time deadlocked ATAK (device run of
     * 2026-10-14, ANR). Every QMESourceManager call goes through this.
     */
    private final Executor mainThread;
    private final AtomicBoolean reattachPending = new AtomicBoolean();
    private final MapCamera camera;
    private ScheduledFuture<?> refresh;
    private int reattachCount;
    private final IonSession session;
    private final ProxyHost proxy;
    private final LoadedAssets loaded;
    private final HttpTransport transport;

    /** @param directory the plugin's folder in ATAK's private storage */
    AssetLoader(File directory, IonSession session, ProxyHost proxy, LoadedAssets loaded, HttpTransport transport,
            MapCamera camera, Executor mainThread) {
        this.camera = camera;
        this.mainThread = mainThread;
        this.streamingDirectory = new File(directory, "streaming");
        this.mapSourcesDirectory = new File(directory, "mapsources");
        this.vectorsDirectory = new File(directory, "vectors");
        this.terrainDirectory = new File(directory, "terrain");
        this.creditsDirectory = new File(directory, "credits");
        this.session = session;
        this.proxy = proxy;
        this.loaded = loaded;
        this.transport = transport;
    }

    /**
     * Lets ATAK's persisted proxy layers stream again after a restart, and
     * re-attaches terrain (which ATAK does not persist) once ATAK has
     * settled. Off the UI thread.
     */
    void registerPersisted() {
        for (LoadedAssets.Entry entry : loaded.all()) {
            if (entry.proxied) {
                proxy.register(entry.assetId);
            }
            if (entry.kind == LoadedAssets.Kind.TERRAIN) {
                long assetId = entry.assetId;
                startupAttaches.add(terrainRefresher.schedule(() -> {
                    if (loaded.get(assetId) == null || terrainLayers.containsKey(assetId)) {
                        return; // removed, or added again by hand, meanwhile
                    }
                    try {
                        attachTerrain(assetId, message -> PluginLog.i(message));
                    } catch (IonException | IOException | RuntimeException | LinkageError failure) {
                        PluginLog.w("Could not re-attach terrain #" + assetId + ": " + failure.getMessage());
                    }
                }, STARTUP_TERRAIN_DELAY_SECONDS, TimeUnit.SECONDS));
            }
        }
    }

    /** Detaches terrain from ATAK when the plugin stops (main thread); the entries stay for the next start. */
    void detachAll() {
        for (ScheduledFuture<?> pending : startupAttaches) {
            pending.cancel(false);
        }
        startupAttaches.clear();
        for (IonTerrainLayer layer : terrainLayers.values()) {
            detachNow(layer);
        }
        terrainLayers.clear();
        stopRefresh();
    }

    private synchronized void startRefresh() {
        if (refresh == null) {
            refresh = terrainRefresher.scheduleWithFixedDelay(this::refreshTerrain, REFRESH_SECONDS,
                    REFRESH_SECONDS, TimeUnit.SECONDS);
        }
    }

    private synchronized void stopRefresh() {
        if (refresh != null) {
            refresh.cancel(false);
            refresh = null;
        }
    }

    private int refreshTicks;
    private final List<ScheduledFuture<?>> startupAttaches = new CopyOnWriteArrayList<>();
    private MapCamera.View lastView;
    private int stillTicks;

    /** Counts refresh ticks with the camera where it was on the previous tick. */
    private boolean cameraStill(MapCamera.View view) {
        MapCamera.View previous = lastView;
        lastView = view;
        boolean same = view != null && previous != null
                && Math.abs(view.latitude - previous.latitude) < 1e-7
                && Math.abs(view.longitude - previous.longitude) < 1e-7
                && Math.abs(view.metresPerPixel - previous.metresPerPixel) <= 1e-6 * Math.max(1.0, previous.metresPerPixel);
        stillTicks = same ? stillTicks + 1 : 0;
        return stillTicks >= STILL_TICKS;
    }

    private void refreshTerrain() {
        try {
            MapCamera.View view = camera.current();
            boolean still = cameraStill(view);
            boolean report = ++refreshTicks % 4 == 0; // about every 12 s
            for (IonTerrainLayer layer : terrainLayers.values()) {
                if (report) {
                    String counts = layer.drainCallCounts();
                    if (!counts.isEmpty()) {
                        PluginLog.i("Terrain #" + layer.assetId() + " ATAK calls in the last ~12 s: " + counts);
                    }
                }
                if (view != null) {
                    double viewDegrees = Math.max(1e-6, view.metresPerPixel * view.widthPixels / 111_320.0);
                    int level = (int) Math.round(Math.log(360.0 / viewDegrees) / Math.log(2));
                    layer.prefetch(view.latitude, view.longitude, Math.max(0, Math.min(15, level)), 2);
                }
                if (still && layer.newTilesSinceAttach() >= MIN_NEW_TILES
                        && System.currentTimeMillis() - layer.attachedAtMillis() >= MIN_REATTACH_MILLIS
                        && reattachPending.compareAndSet(false, true)) {
                    mainThread.execute(() -> reattach(layer));
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            PluginLog.w("Terrain refresh failed: " + failure);
        }
    }

    /** Main thread only. */
    private void reattach(IonTerrainLayer layer) {
        try {
            if (terrainLayers.get(layer.assetId()) != layer) {
                return; // removed or replaced meanwhile
            }
            long started = System.nanoTime();
            QMESourceManager.detachQMESourceLayer(layer);
            long detached = System.nanoTime();
            QMESourceManager.attachQMESourceLayer(layer);
            long attached = System.nanoTime();
            layer.markAttached();
            reattachCount++;
            // Runs on the main thread: report how long ATAK held it.
            long detachMs = (detached - started) / 1_000_000;
            long attachMs = (attached - detached) / 1_000_000;
            if (reattachCount <= 5 || reattachCount % 20 == 0 || detachMs + attachMs > 500) {
                PluginLog.i("Terrain #" + layer.assetId() + " re-attached with " + layer.cachedCount()
                        + " tiles cached (" + reattachCount + " re-attaches; detach " + detachMs
                        + " ms, attach " + attachMs + " ms on the main thread).");
            }
        } catch (RuntimeException | LinkageError failure) {
            PluginLog.w("Re-attaching terrain failed: " + failure);
        } finally {
            reattachPending.set(false);
        }
    }

    /** Diagnostics: the elevation sources ATAK knows, read reflectively. */
    private static void logElevationSources() {
        try {
            Class<?> manager = Class.forName("com.atakmap.map.elevation.ElevationSourceManager");
            List<Object> sources = new ArrayList<>();
            manager.getMethod("getSources", Collection.class).invoke(null, sources);
            StringBuilder names = new StringBuilder();
            for (Object source : sources) {
                Object name = source.getClass().getMethod("getName").invoke(source);
                Object bounds = source.getClass().getMethod("getBounds").invoke(source);
                names.append("\n  ").append(name).append(" ").append(bounds);
            }
            PluginLog.i("ATAK elevation sources (" + sources.size() + "):" + names);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            PluginLog.w("Could not list elevation sources: " + unavailable);
        }
    }

    List<LoadedAssets.Entry> loadedEntries() {
        return loaded.all();
    }

    /** The Google logo ion's attribution asks to show with {@code assetId}, cached locally; or null. */
    File creditLogo(long assetId) {
        return isLoaded(assetId) ? creditLogos.get(assetId) : null;
    }

    /**
     * Downloads a credit image from ion's asset host once (no credentials
     * needed or sent) into private storage. Best effort: a missing logo
     * leaves the text credit.
     */
    private File creditImage(String source) {
        String fileName = source.substring(source.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (!fileName.endsWith(".png")) {
            return null;
        }
        File file = new File(creditsDirectory, fileName);
        if (file.isFile()) {
            return file;
        }
        try {
            HttpResponse response = transport.send(HttpRequest.get(URI.create(source)).build());
            byte[] body = response.body();
            boolean png = body.length > 8 && body[0] == (byte) 0x89 && body[1] == 'P' && body[2] == 'N' && body[3] == 'G';
            if (!response.isSuccessful() || !png || body.length > MAX_CREDIT_IMAGE_BYTES) {
                PluginLog.w("Credit image " + fileName + " not usable (HTTP " + response.status() + ").");
                return null;
            }
            if (!creditsDirectory.isDirectory() && !creditsDirectory.mkdirs()) {
                return null;
            }
            File part = new File(creditsDirectory, fileName + ".part");
            try (FileOutputStream output = new FileOutputStream(part)) {
                output.write(body);
            }
            return part.renameTo(file) ? file : null;
        } catch (IOException | RuntimeException failure) {
            PluginLog.w("Credit image " + fileName + " not downloaded: " + failure.getMessage());
            return null;
        }
    }

    boolean isLoaded(long assetId) {
        return loaded.get(assetId) != null;
    }

    Result load(long assetId, String name, Consumer<String> log) throws IonException, IOException {
        session.client(); // fails early with a clear message when not configured
        AssetEndpoint endpoint = session.endpoint(assetId);
        for (Attribution attribution : endpoint.attributions()) {
            log.accept("Attribution: " + Redactor.redact(attribution.html()));
            for (Attribution.Image image : attribution.images(CREDIT_IMAGE_HOST)) {
                if (image.alt.toLowerCase(Locale.ROOT).contains("google")) {
                    File logo = creditImage(image.source);
                    if (logo != null) {
                        creditLogos.put(assetId, logo);
                    }
                }
            }
        }
        if (remove(assetId)) {
            log.accept("Replaced the layer loaded earlier for asset " + assetId + ".");
        }
        switch (endpoint.type()) {
            case TILES_3D:
                return loadTileset(endpoint, name, log);
            case IMAGERY:
                return loadImagery(endpoint, name, log);
            case GEOJSON:
            case KML:
                return loadVector(endpoint, name, log);
            case TERRAIN:
                return loadTerrain(endpoint, name, log);
            case CZML:
                throw unsupported(assetId, "ATAK has no CZML importer.");
            default:
                throw unsupported(assetId, endpoint.type().ionName() + " assets are not supported in ATAK yet.");
        }
    }

    /** @return whether a layer was loaded for {@code assetId} */
    boolean remove(long assetId) {
        LoadedAssets.Entry entry = loaded.get(assetId);
        if (entry == null) {
            return false;
        }
        File file = new File(entry.file);
        switch (entry.kind) {
            case TILES_3D:
                AtakImports.removeTileset(file);
                break;
            case IMAGERY:
                AtakImports.removeMapSource(file);
                break;
            case TERRAIN:
                IonTerrainLayer layer = terrainLayers.remove(assetId);
                if (layer != null) {
                    detach(layer);
                }
                if (terrainLayers.isEmpty()) {
                    stopRefresh();
                }
                deleteRecursively(file);
                break;
            default:
                AtakImports.removeVector(file);
                deleteIfEmpty(file.getParentFile());
                break;
        }
        if (entry.proxied) {
            proxy.unregister(assetId);
        }
        loaded.remove(assetId);
        return true;
    }

    /**
     * Removes every layer this plugin gave ATAK, including files left by
     * earlier builds and by the WebView plugin it replaces.
     *
     * @return how many layers were removed
     */
    int removeAll(Consumer<String> log) {
        int removed = 0;
        for (LoadedAssets.Entry entry : loaded.all()) {
            remove(entry.assetId);
            log.accept("Removed " + entry.name + " (#" + entry.assetId + ").");
            removed++;
        }
        // Untracked files: earlier builds, the WebView plugin, or lost state.
        for (File source : cesiumFiles(AtakImports.sharedMapSourcesDirectory(), ".xml")) {
            AtakImports.removeMapSource(source);
            log.accept("Removed stale map source " + source.getName() + " from shared storage.");
            removed++;
        }
        for (File source : cesiumFiles(mapSourcesDirectory, ".xml")) {
            AtakImports.removeMapSource(source);
            log.accept("Removed stale map source " + source.getName() + ".");
            removed++;
        }
        File[] terrainFolders = terrainDirectory.listFiles(File::isDirectory);
        for (File folder : terrainFolders == null ? new File[0] : terrainFolders) {
            deleteRecursively(folder);
            log.accept("Removed a stale terrain cache (" + folder.getName() + ").");
            removed++;
        }
        File[] vectorFolders = vectorsDirectory.listFiles(File::isDirectory);
        for (File folder : vectorFolders == null ? new File[0] : vectorFolders) {
            File[] files = folder.listFiles();
            for (File file : files == null ? new File[0] : files) {
                AtakImports.removeVector(file);
                log.accept("Removed stale overlay " + file.getName() + ".");
                removed++;
            }
            deleteIfEmpty(folder);
        }
        for (File config : cesiumFiles(streamingDirectory, ".json")) {
            AtakImports.removeTileset(config);
            log.accept("Removed stale 3D Tiles layer " + config.getName() + ".");
            removed++;
        }
        return removed;
    }

    /**
     * Attaches ion quantized-mesh terrain to ATAK's elevation, replacing any
     * other ion terrain (ATAK blends sources; two global terrains would fight).
     */
    private Result loadTerrain(AssetEndpoint endpoint, String name, Consumer<String> log)
            throws IonException, IOException {
        long assetId = endpoint.assetId();
        for (LoadedAssets.Entry other : loaded.all()) {
            if (other.kind == LoadedAssets.Kind.TERRAIN && other.assetId != assetId) {
                remove(other.assetId);
                log.accept("Removed the other terrain, " + other.name + ".");
            }
        }
        attachTerrain(assetId, log);
        LoadedAssets.Entry entry = new LoadedAssets.Entry(assetId, LoadedAssets.Kind.TERRAIN, name,
                terrainFolder(assetId).getAbsolutePath(), false);
        loaded.put(entry);
        return new Result(entry, false, null);
    }

    private void attachTerrain(long assetId, Consumer<String> log) throws IonException, IOException {
        IonTerrainLayer layer = new IonTerrainLayer(assetId, terrainFolder(assetId), session, transport,
                terrainDownloads);
        TerrainLayout layout = layer.open();
        IonTerrainLayer previous = terrainLayers.put(assetId, layer);
        mainThread.execute(() -> {
            if (previous != null) {
                detachNow(previous);
            }
            if (terrainLayers.get(assetId) == layer) {
                long started = System.nanoTime();
                QMESourceManager.attachQMESourceLayer(layer);
                PluginLog.i("Terrain #" + assetId + " attached in " + (System.nanoTime() - started) / 1_000_000
                        + " ms on the main thread.");
                layer.markAttached();
                logElevationSources();
            }
        });
        startRefresh();
        log.accept("Terrain attached: levels " + layout.minZoom() + "-" + layout.maxZoom()
                + ". Tiles download as ATAK asks for them.");
    }

    private File terrainFolder(long assetId) {
        return new File(terrainDirectory, Long.toString(assetId));
    }

    /** Stops the layer now and detaches it on the main thread. */
    private void detach(IonTerrainLayer layer) {
        layer.setEnabled(false);
        mainThread.execute(() -> detachNow(layer));
    }

    /** Main thread only. */
    private static void detachNow(IonTerrainLayer layer) {
        layer.setEnabled(false);
        try {
            QMESourceManager.detachQMESourceLayer(layer);
        } catch (RuntimeException | LinkageError failure) {
            PluginLog.w("Detaching terrain failed: " + failure);
        }
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

    private static void deleteIfEmpty(File folder) {
        String[] remaining = folder == null ? null : folder.list();
        if (remaining != null && remaining.length == 0 && !folder.delete()) {
            PluginLog.w("Could not delete " + folder);
        }
    }

    private static File[] cesiumFiles(File directory, String suffix) {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("cesium-ion-") && name.endsWith(suffix));
        return files == null ? new File[0] : files;
    }

    private Result loadTileset(AssetEndpoint endpoint, String name, Consumer<String> log) throws IOException {
        long assetId = endpoint.assetId();
        boolean photorealistic = endpoint instanceof AssetEndpoint.External3DTiles
                && ((AssetEndpoint.External3DTiles) endpoint).isGooglePhotorealistic();
        // Google's tileset goes through the proxy too, so ion's Google key
        // never reaches ATAK's model database, and ATAK can delete the layer
        // (it refuses to delete an https model URI).
        requireProxy();
        proxy.register(assetId);
        String url = proxy.rootUrl(endpoint);
        TilesetBounds bounds = photorealistic ? null : locate(endpoint, log);
        File config = AtakImports.importTileset(streamingDirectory, assetId, name, url);
        LoadedAssets.Entry entry = new LoadedAssets.Entry(
                assetId, LoadedAssets.Kind.TILES_3D, name, config.getAbsolutePath(), true);
        loaded.put(entry);
        log.accept("3D Tiles import requested (through the local proxy).");
        return new Result(entry, photorealistic, bounds);
    }

    private Result loadImagery(AssetEndpoint endpoint, String name, Consumer<String> log)
            throws IonException, IOException {
        long assetId = endpoint.assetId();
        TileTemplate template = new ImageryResolver(transport).resolve(endpoint);
        log.accept("Imagery: " + Redactor.redact(template.toString()));
        String url;
        if (template.needsProxy()) {
            requireProxy();
            proxy.register(assetId);
            url = proxy.proxyUrl(endpoint, template.url());
        } else {
            url = template.url();
        }
        String layerName = LayerNames.imageryLayerName(name);
        File source = AtakImports.importMapSource(
                mapSourcesDirectory, assetId, MapSourceXml.xml(layerName, template, url));
        LoadedAssets.Entry entry = new LoadedAssets.Entry(
                assetId, LoadedAssets.Kind.IMAGERY, layerName, source.getAbsolutePath(), template.needsProxy());
        loaded.put(entry);
        return new Result(entry, false, null);
    }

    /**
     * Downloads an ion-hosted GeoJSON, KML, or KMZ file into private storage
     * (with the token only on the download request) and imports it.
     */
    private Result loadVector(AssetEndpoint endpoint, String name, Consumer<String> log)
            throws IonException, IOException {
        long assetId = endpoint.assetId();
        HttpResponse response = transport.send(
                HttpRequest.get(URI.create(endpoint.authorize(endpoint.url()))).build());
        if (!response.isSuccessful()) {
            throw new IonException(IonException.Kind.SERVER,
                    "Downloading asset " + assetId + " returned HTTP " + response.status() + ".",
                    response.status(), null);
        }
        byte[] body = response.body();
        String extension = LayerNames.vectorExtension(endpoint.type(), body);
        // One folder per asset: ATAK names the overlay after the file.
        File folder = new File(vectorsDirectory, Long.toString(assetId));
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("Could not create " + folder);
        }
        byte[] content = body;
        String format = extension.substring(1);
        if (extension.equals(".geojson")) {
            // ATAK drapes GeoJSON on its terrain surface, which photorealistic
            // 3D Tiles hide. As KML a little above the ground it draws as 3D
            // and stays visible over them.
            String kml = GeoJsonToKml.convert(new String(body, StandardCharsets.UTF_8), name,
                    VECTOR_ALTITUDE_METRES);
            if (kml != null) {
                content = kml.getBytes(StandardCharsets.UTF_8);
                extension = ".kml";
                format = "GeoJSON as KML, " + (int) VECTOR_ALTITUDE_METRES + " m above the ground";
            }
        }
        File file = new File(folder, LayerNames.safeName(name, "overlay") + extension);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content);
        }
        AtakImports.importVector(file);
        LoadedAssets.Entry entry = new LoadedAssets.Entry(
                assetId, LoadedAssets.Kind.VECTOR, name, file.getAbsolutePath(), false);
        loaded.put(entry);
        log.accept("Overlay import requested (" + body.length / 1024 + " KB, " + format + ").");
        TilesetBounds bounds = LayerNames.vectorExtension(endpoint.type(), body).equals(".geojson")
                ? GeoJsonBounds.of(new String(body, StandardCharsets.UTF_8)) : null;
        if (bounds != null && bounds.radius() > MAX_FLY_TO_RADIUS_METRES) {
            bounds = null;
        }
        return new Result(entry, false, bounds);
    }

    /** Reads the root tileset's bounding volume; best effort. */
    private TilesetBounds locate(AssetEndpoint endpoint, Consumer<String> log) {
        try {
            HttpResponse response = transport.send(
                    HttpRequest.get(URI.create(endpoint.authorize(endpoint.url()))).build());
            if (!response.isSuccessful()) {
                log.accept("Could not read the tileset root (HTTP " + response.status() + ").");
                return null;
            }
            TilesetBounds bounds = TilesetBounds.of(response.bodyText());
            if (bounds == null) {
                return null;
            }
            log.accept("Tileset location: " + bounds);
            if (bounds.radius() > MAX_FLY_TO_RADIUS_METRES) {
                log.accept("The tileset covers the globe; the map stays where it is.");
                return null;
            }
            return bounds;
        } catch (IOException | RuntimeException failure) {
            log.accept("Could not read the tileset root: " + Redactor.redact(String.valueOf(failure.getMessage())));
            return null;
        }
    }

    private void requireProxy() throws IOException {
        if (!proxy.isRunning()) {
            throw new IOException("The local tile proxy is not running; see the log for why it failed to start.");
        }
    }

    private static IonException unsupported(long assetId, String message) {
        return new IonException(IonException.Kind.CONFIGURATION, "Asset " + assetId + ": " + message);
    }
}
