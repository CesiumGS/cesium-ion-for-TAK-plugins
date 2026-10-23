package com.cesium.atak.ion;

import com.atakmap.android.maps.MapView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Names of ATAK's map layers, read reflectively (MapView.getLayers and
 * MultiLayer are not in the plugin's SDK stubs): the imagery selection
 * broadcast needs the name of the raster layer that holds a map source.
 */
final class MapLayerNames {
    /** Tried when the layers cannot be read; a name that matches no layer is ignored by ATAK. */
    static final String MOBILE = "Mobile";
    static final List<String> FALLBACK = Arrays.asList("Mobile", "Mobile Imagery", "Native", "Imagery");
    private static final int MAX_LAYERS = 64;

    private MapLayerNames() {
    }

    /** Layer names under MapView's MAP_LAYERS stack, depth first; empty if unreadable. */
    static List<String> list(MapView mapView) {
        List<String> names = new ArrayList<>();
        try {
            Class<?> stack = Class.forName("com.atakmap.android.maps.MapView$RenderStack");
            Object mapLayers = enumConstant(stack, "MAP_LAYERS");
            Method getLayers = mapView.getClass().getMethod("getLayers", stack);
            collect((Collection<?>) getLayers.invoke(mapView, mapLayers), names, 0);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            PluginLog.w("Map layers not readable: " + unreadable);
        }
        return names;
    }

    /**
     * The raster layer named {@code layerName} under MAP_LAYERS, or null.
     * Its RasterLayer2 methods (getSelectionOptions, setSelection) are
     * called reflectively by {@link #selectionOptions} and {@link #select}.
     */
    static Object find(MapView mapView, String layerName) {
        try {
            Class<?> stack = Class.forName("com.atakmap.android.maps.MapView$RenderStack");
            Method getLayers = mapView.getClass().getMethod("getLayers", stack);
            return find((Collection<?>) getLayers.invoke(mapView, enumConstant(stack, "MAP_LAYERS")), layerName, 0);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /** The selection names a raster layer offers (its map sources), or empty. */
    static List<String> selectionOptions(Object rasterLayer) {
        List<String> options = new ArrayList<>();
        try {
            Object result = rasterLayer.getClass().getMethod("getSelectionOptions").invoke(rasterLayer);
            if (result instanceof Collection) {
                for (Object option : (Collection<?>) result) {
                    options.add(String.valueOf(option));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            PluginLog.w("Selection options not readable: " + unreadable);
        }
        return options;
    }

    /** A raster layer's current selection, or null (auto-select, or unreadable). */
    static String selection(Object rasterLayer) {
        try {
            Object selection = rasterLayer.getClass().getMethod("getSelection").invoke(rasterLayer);
            return selection == null ? null : selection.toString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /** Sets a raster layer's selection directly (null: auto-select); returns whether the call succeeded. */
    static boolean select(Object rasterLayer, String selection) {
        try {
            rasterLayer.getClass().getMethod("setSelection", String.class).invoke(rasterLayer, selection);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            PluginLog.w("Could not set the selection: " + failure);
            return false;
        }
    }

    private static Object find(Collection<?> layers, String layerName, int depth) throws ReflectiveOperationException {
        if (layers == null || depth > 4) {
            return null;
        }
        for (Object layer : layers) {
            if (layerName.equals(layer.getClass().getMethod("getName").invoke(layer))) {
                return layer;
            }
            Method children = findNoArg(layer.getClass(), "getLayers");
            Object nested = children == null ? null : children.invoke(layer);
            if (nested instanceof Collection) {
                Object found = find((Collection<?>) nested, layerName, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The layer that holds map sources: "Mobile" when ATAK has it (5.7 does);
     * otherwise every layer found, then the fallbacks. Selecting in a layer
     * also activates it, so only the one that holds the source is sent.
     */
    static List<String> selectionCandidates(List<String> layerNames) {
        if (layerNames.contains(MOBILE)) {
            return Collections.singletonList(MOBILE);
        }
        Set<String> candidates = new LinkedHashSet<>(layerNames);
        candidates.addAll(FALLBACK);
        return new ArrayList<>(candidates);
    }

    private static void collect(Collection<?> layers, List<String> names, int depth) throws ReflectiveOperationException {
        if (layers == null || depth > 4) {
            return;
        }
        for (Object layer : layers) {
            if (names.size() >= MAX_LAYERS) {
                return;
            }
            Object name = layer.getClass().getMethod("getName").invoke(layer);
            if (name != null) {
                names.add(name.toString());
            }
            Method children = findNoArg(layer.getClass(), "getLayers");
            if (children != null) {
                Object nested = children.invoke(layer);
                if (nested instanceof Collection) {
                    collect((Collection<?>) nested, names, depth + 1);
                }
            }
        }
    }

    private static Method findNoArg(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static Object enumConstant(Class<?> type, String name) {
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new IllegalStateException("No " + type.getSimpleName() + "." + name);
    }
}
