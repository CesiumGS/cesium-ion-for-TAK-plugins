package com.cesium.atak.ion;

import com.atakmap.android.maps.MapTouchController;
import com.atakmap.android.maps.MapView;

/**
 * Two-finger rotate and tilt. ATAK ships with them gated: user rotation off
 * and tilt in a disabled state until enabled (normally from the compass).
 * Uses the ATAK 5.7 {@link MapTouchController} API confirmed by the SDK scan.
 */
final class MapGestures {
    private MapGestures() {
    }

    /** @return the resulting state, for the pane log */
    static String enableRotateAndTilt(MapView mapView) {
        MapTouchController touch = mapView.getMapTouchController();
        if (touch == null) {
            return "ATAK has no MapTouchController available.";
        }
        touch.setUserOrientation(true);
        touch.setTiltEnabledState(MapTouchController.STATE_TILT_ENABLED);
        return state(touch, mapView);
    }

    static String state(MapView mapView) {
        MapTouchController touch = mapView.getMapTouchController();
        return touch == null ? "no MapTouchController" : state(touch, mapView);
    }

    private static String state(MapTouchController touch, MapView mapView) {
        return "userRotation=" + touch.isUserOrientationEnabled()
                + ", tiltState=" + touch.getTiltEnabledState()
                + " (enabled=" + MapTouchController.STATE_TILT_ENABLED + ")"
                + ", maxTilt=" + mapView.getMaxMapTilt();
    }
}
