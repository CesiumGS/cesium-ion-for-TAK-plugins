package com.cesium.atak.ion;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Where the map is looking, read reflectively: the 5.7 getters are not in
 * the plugin's SDK stubs, and a wrong guess must degrade (no prefetch), not
 * crash. Tries AtakMapView's getLatitude/getLongitude/getMapResolution, then
 * getCenterPoint().get().
 */
final class MapCamera {
    /** Latitude and longitude in degrees, and metres per pixel. */
    static final class View {
        final double latitude;
        final double longitude;
        final double metresPerPixel;
        final int widthPixels;

        View(double latitude, double longitude, double metresPerPixel, int widthPixels) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.metresPerPixel = metresPerPixel;
            this.widthPixels = widthPixels;
        }
    }

    private final MapView mapView;
    private boolean reported;

    MapCamera(MapView mapView) {
        this.mapView = mapView;
    }

    /** @return {@code null} when this ATAK build exposes no usable getters */
    View current() {
        try {
            double latitude;
            double longitude;
            Object center = call(mapView, "getCenterPoint");
            if (center != null && hasMethod(center, "get")) {
                center = call(center, "get");
            }
            if (center != null && hasMethod(center, "getLatitude")) {
                latitude = ((Number) call(center, "getLatitude")).doubleValue();
                longitude = ((Number) call(center, "getLongitude")).doubleValue();
            } else {
                latitude = ((Number) call(mapView, "getLatitude")).doubleValue();
                longitude = ((Number) call(mapView, "getLongitude")).doubleValue();
            }
            double resolution = ((Number) call(mapView, "getMapResolution")).doubleValue();
            if (!reported) {
                reported = true;
                PluginLog.i(String.format(Locale.US, "Map camera readable: %.4f, %.4f, %.1f m/px",
                        latitude, longitude, resolution));
            }
            return new View(latitude, longitude, resolution, Math.max(1, mapView.getWidth()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            if (!reported) {
                reported = true;
                PluginLog.w("Map camera not readable (" + unavailable + "); terrain prefetch is off.");
            }
            return null;
        }
    }

    /**
     * Points the camera at a place in one call: centre (with its height in
     * metres HAE, or NaN), metres per pixel and tilt (degrees; NaN keeps the
     * current tilt), keeping the rotation. Main thread.
     *
     * <p>Uses MapRenderer3.lookAt, which AtakMapController.panZoomTo ends in.
     * panZoomTo first looks up the terrain height at the point on the calling
     * thread (ElevationManager.getElevation), which blocks ATAK's main thread
     * for tens of seconds while quantized-mesh terrain loads. Without a height
     * the camera frames the point at sea level, which puts a tilted view inside
     * high ground; {@link #groundHeight} finds the height off the main thread.
     *
     * @return false when this ATAK build has no lookAt (use the controller)
     */
    boolean lookAt(double latitude, double longitude, double height, double metresPerPixel, double tilt,
            boolean animate) {
        try {
            Object renderer = call(mapView, "getRenderer3");
            double rotation = number(mapView, "getMapRotation", 0.0);
            double tiltNow = Double.isNaN(tilt) ? number(mapView, "getMapTilt", 0.0) : tilt;
            GeoPoint point = Double.isNaN(height) ? new GeoPoint(latitude, longitude)
                    : new GeoPoint(latitude, longitude, height);
            Method lookAt = renderer.getClass().getMethod("lookAt", GeoPoint.class, double.class, double.class,
                    double.class, boolean.class);
            lookAt.invoke(renderer, point, metresPerPixel, rotation, tiltNow, animate);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            PluginLog.w("MapRenderer3.lookAt unavailable (" + unavailable + "); using the map controller.");
            return false;
        }
    }

    /**
     * Looks up the ground height (metres HAE) at a point off the main thread,
     * as ATAK's terrain knows it, and hands it to {@code onMainThread}: NaN
     * when unknown, or when the lookup takes longer than {@code waitMillis}
     * (it waits on ATAK's terrain locks while tiles load).
     */
    void groundHeight(double latitude, double longitude, long waitMillis, Consumer<Double> onMainThread) {
        AtomicBoolean answered = new AtomicBoolean();
        Thread lookup = new Thread(() -> {
            double height = elevation(latitude, longitude);
            if (!Double.isNaN(height)) {
                remember(latitude, longitude, height);
            }
            mapView.post(() -> {
                if (answered.compareAndSet(false, true)) {
                    onMainThread.accept(height);
                }
            });
        }, "cesium-ion-ground-height");
        lookup.setDaemon(true);
        lookup.start();
        mapView.postDelayed(() -> {
            if (answered.compareAndSet(false, true)) {
                double nearby = recentHeightNear(latitude, longitude);
                PluginLog.i("Ground height lookup took over " + waitMillis + " ms; "
                        + (Double.isNaN(nearby) ? "framing without it." : "using the last height found nearby."));
                onMainThread.accept(nearby);
            }
        }, waitMillis);
    }

    /** The last ground height found, with where: lookups wait on terrain locks while tiles load. */
    private double[] lastGround;

    private synchronized void remember(double latitude, double longitude, double height) {
        lastGround = new double[] {latitude, longitude, height};
    }

    /** The last height found within about 20 km, or NaN. */
    private synchronized double recentHeightNear(double latitude, double longitude) {
        double[] last = lastGround;
        if (last == null || Math.abs(last[0] - latitude) > 0.18 || Math.abs(last[1] - longitude) > 0.25) {
            return Double.NaN;
        }
        return last[2];
    }

    private static double elevation(double latitude, double longitude) {
        try {
            Class<?> manager = Class.forName("com.atakmap.map.elevation.ElevationManager");
            Class<?> parameters = Class.forName("com.atakmap.map.elevation.ElevationManager$QueryParameters");
            Object height = manager.getMethod("getElevation", double.class, double.class, parameters)
                    .invoke(null, latitude, longitude, null);
            return ((Number) height).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return Double.NaN;
        }
    }

    private static double number(Object target, String getter, double fallback) {
        try {
            return ((Number) call(target, getter)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return fallback;
        }
    }

    /** One line for logs: centre and resolution, or why the camera is unreadable. */
    String describe() {
        View view = current();
        if (view == null) {
            return "unreadable";
        }
        String tilt;
        try {
            tilt = String.format(Locale.US, "%.0f", ((Number) call(mapView, "getMapTilt")).doubleValue());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            tilt = "?";
        }
        return String.format(Locale.US, "%.4f, %.4f, %.2f m/px (%.0f m across), tilt %s",
                view.latitude, view.longitude, view.metresPerPixel, view.metresPerPixel * view.widthPixels, tilt);
    }

    private static boolean hasMethod(Object target, String name) {
        try {
            target.getClass().getMethod(name);
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }
}
