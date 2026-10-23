package com.cesium.atak.ion;

import com.atakmap.android.maps.MapView;
import com.atakmap.map.MapRenderer3;
import com.atakmap.map.layer.control.SurfaceRendererControl;
import com.atakmap.map.layer.control.TerrainBlendControl;
import com.atakmap.map.layer.model.Mesh;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Fades ATAK's basemap surface (imagery draped on its own terrain) through
 * {@link TerrainBlendControl}, so photorealistic 3D Tiles are not cut through
 * by a second, coarser ground surface. CesiumJS does the equivalent by hiding
 * its globe under Google Photorealistic 3D Tiles.
 *
 * <p>Fading the fill leaves ATAK drawing the terrain mesh's lines (an "x-ray"
 * wireframe), so the line draw modes of {@link SurfaceRendererControl} are
 * switched off while faded and restored afterwards.
 *
 * <p>The renderer is located reflectively because the {@code MapView}
 * accessor for it is not part of the API this plugin has verified against the
 * ATAK 5.7 SDK; {@code MapRenderer3} and {@code TerrainBlendControl} are.
 */
final class BasemapBlend {
    private TerrainBlendControl control;
    private SurfaceRendererControl surface;
    private MapRenderer3 renderer;
    private String description = "not looked up yet";
    /** Line draw modes this class disabled, so exactly those are restored. */
    private final Set<Mesh.DrawMode> disabledLineModes = EnumSet.noneOf(Mesh.DrawMode.class);

    /** @return the control, or {@code null} when this ATAK build does not expose one */
    TerrainBlendControl control(MapView mapView) {
        if (control == null) {
            renderer = findRenderer(mapView);
            if (renderer == null) {
                description = "no MapRenderer3 reachable from MapView";
                return null;
            }
            control = renderer.getControl(TerrainBlendControl.class);
            surface = renderer.getControl(SurfaceRendererControl.class);
            description = control == null
                    ? "renderer " + renderer.getClass().getName() + " has no TerrainBlendControl"
                    : control.getClass().getName();
        }
        return control;
    }

    /** What was found, for the pane log. */
    String description() {
        return description;
    }

    /**
     * @param opacity 1 = ATAK's normal opaque basemap, 0 = fully faded
     * @return whether a control was available
     */
    boolean apply(MapView mapView, double opacity) {
        TerrainBlendControl blend = control(mapView);
        if (blend == null) {
            return false;
        }
        double clamped = Math.max(0.0, Math.min(1.0, opacity));
        blend.setBlendFactor(clamped);
        blend.setEnabled(clamped < 1.0);
        setTerrainLinesHidden(clamped < 1.0);
        return true;
    }

    private void setTerrainLinesHidden(boolean hidden) {
        if (surface == null) {
            return;
        }
        if (hidden) {
            for (Mesh.DrawMode mode : Mesh.DrawMode.values()) {
                if (isLineMode(mode) && surface.isDrawModeEnabled(mode)) {
                    surface.disableDrawMode(mode);
                    disabledLineModes.add(mode);
                }
            }
        } else {
            for (Mesh.DrawMode mode : disabledLineModes) {
                surface.enableDrawMode(mode);
            }
            disabledLineModes.clear();
        }
        surface.markDirty();
    }

    private static boolean isLineMode(Mesh.DrawMode mode) {
        return mode.name().toLowerCase(Locale.ROOT).contains("line");
    }

    /** Current state as "enabled=…, factor=…", for diagnostics. */
    String state(MapView mapView) {
        TerrainBlendControl blend = control(mapView);
        if (blend == null) {
            return description;
        }
        StringBuilder state = new StringBuilder("enabled=" + blend.getEnabled() + ", factor=" + blend.getBlendFactor());
        if (surface == null) {
            state.append(", no SurfaceRendererControl");
        } else {
            state.append(", surface draw modes:");
            for (Mesh.DrawMode mode : Mesh.DrawMode.values()) {
                state.append(' ').append(mode.name()).append('=').append(surface.isDrawModeEnabled(mode));
            }
        }
        return state.toString();
    }

    /** Public no-argument getters on MapView (then one level deeper) that yield a MapRenderer3. */
    private static MapRenderer3 findRenderer(MapView mapView) {
        if (mapView instanceof MapRenderer3) {
            return (MapRenderer3) mapView;
        }
        MapRenderer3 direct = searchGetters(mapView, 1);
        if (direct != null) {
            return direct;
        }
        return searchGetters(mapView, 2);
    }

    private static MapRenderer3 searchGetters(Object target, int depth) {
        if (target == null || depth <= 0) {
            return null;
        }
        for (Method method : target.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0
                    || Modifier.isStatic(method.getModifiers())
                    || !method.getName().startsWith("get")
                    || method.getDeclaringClass() == Object.class) {
                continue;
            }
            Class<?> type = method.getReturnType();
            boolean renderer = MapRenderer3.class.isAssignableFrom(type);
            // Only descend into renderer/surface-like objects, never arbitrary getters.
            String simpleName = type.getSimpleName();
            boolean worthDescending = depth > 1
                    && type.getName().startsWith("com.atakmap.")
                    && (simpleName.contains("GL") || simpleName.contains("Surface") || simpleName.contains("Render"));
            if (!renderer && !worthDescending) {
                continue;
            }
            try {
                Object value = method.invoke(target);
                if (value instanceof MapRenderer3) {
                    PluginLog.i("MapRenderer3 via " + target.getClass().getSimpleName() + "." + method.getName() + "()");
                    return (MapRenderer3) value;
                }
                if (worthDescending && value != null && value != target) {
                    MapRenderer3 nested = searchGetters(value, depth - 1);
                    if (nested != null) {
                        return nested;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Getters with side effects or unmet preconditions are skipped.
            }
        }
        return null;
    }
}
