package com.cesium.atak.ion;

import com.atakmap.android.maps.MapView;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Data credits drawn on the map, bottom centre: Google Photorealistic 3D
 * Tiles require Google's logo and the copyrights of the visible tiles on
 * screen. A horizontal row holds the logo (when ion's attribution supplied
 * one) and the credit text.
 *
 * <p>ATAK 5.7's widgets (RootLayoutWidget under the map view's
 * "rootLayoutWidget" extra, LinearLayoutWidget, TextWidget,
 * MarkerIconWidget, Icon.Builder) are reached reflectively: they are not in
 * the plugin's SDK stubs, and a mismatch must cost the overlay (or just the
 * logo), not the plugin. UI thread only.
 */
final class CreditOverlay {
    private static final int BOTTOM_EDGE = 7;
    private static final int WRAP_CONTENT = -2;
    private static final int HORIZONTAL = 0;
    private static final int TEXT_SIZE = 2;
    private static final int TRANSLUCENT_BLACK = 0x99000000;
    private static final int LOGO_HEIGHT_PX = 36;

    private final MapView mapView;
    private Object row;
    private Object text;
    private Object logo;
    private File logoFile;
    private boolean failed;
    private boolean logoFailed;
    private String shown = "";
    private boolean placementLogged;

    CreditOverlay(MapView mapView) {
        this.mapView = mapView;
    }

    /** Shows {@code credits} with {@code logo} (may be null); empty credits hide the overlay. */
    void show(String credits, File logo) {
        if (failed || (credits.equals(shown) && sameFile(logo, logoFile))) {
            return;
        }
        try {
            if (row == null) {
                if (credits.isEmpty()) {
                    return;
                }
                create();
            }
            call(text, "setText", new Class<?>[] {String.class}, credits);
            if (!sameFile(logo, logoFile) && !logoFailed) {
                setLogo(logo);
            }
            call(row, "setVisible", new Class<?>[] {boolean.class}, !credits.isEmpty());
            shown = credits;
            if (!credits.isEmpty() && !placementLogged) {
                placementLogged = true;
                // After ATAK has laid the row out: where it is, to diagnose credits not seen on screen.
                mapView.postDelayed(this::logPlacement, 3000);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            failed = true;
            PluginLog.w("Map credits cannot be drawn in this ATAK (" + unavailable
                    + "); they are listed in the Cesium ion pane log instead.");
        }
    }

    void hide() {
        show("", logoFile);
    }

    /** Removes the row from ATAK's layout. */
    void dispose() {
        if (row == null) {
            return;
        }
        try {
            Object layout = bottomLayout();
            if (!invokeFirst(layout, new String[] {"removeWidget", "removeChildWidget"}, row)) {
                call(row, "setVisible", new Class<?>[] {boolean.class}, false);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // ATAK is shutting the map down anyway.
        }
        row = null;
        text = null;
        logo = null;
        logoFile = null;
        shown = "";
    }

    private void create() throws ReflectiveOperationException {
        Class<?> linear = Class.forName("com.atakmap.android.widgets.LinearLayoutWidget");
        Object created = linear.getConstructor(int.class, int.class, int.class)
                .newInstance(WRAP_CONTENT, WRAP_CONTENT, HORIZONTAL);
        call(created, "setMargins", new Class<?>[] {float.class, float.class, float.class, float.class},
                0f, 0f, 0f, 8f);
        Class<?> textWidget = Class.forName("com.atakmap.android.widgets.TextWidget");
        Object label = textWidget.getConstructor(String.class, int.class).newInstance("", TEXT_SIZE);
        call(label, "setBackground", new Class<?>[] {int.class}, TRANSLUCENT_BLACK);
        if (!invokeFirst(created, new String[] {"addWidget", "addChildWidget"}, label)) {
            throw new NoSuchMethodException("no addWidget on " + created.getClass().getName());
        }
        Object layout = bottomLayout();
        if (!invokeFirst(layout, new String[] {"addWidget", "addChildWidget"}, created)) {
            throw new NoSuchMethodException("no addWidget on " + layout.getClass().getName());
        }
        row = created;
        text = label;
        PluginLog.i("Map credits overlay added (bottom centre).");
    }

    /** Puts the logo before the text; on any failure keeps the text credit alone. */
    private void setLogo(File file) {
        try {
            if (logo == null && file != null) {
                Class<?> marker = Class.forName("com.atakmap.android.widgets.MarkerIconWidget");
                Object created = marker.getConstructor().newInstance();
                if (!invokeAt(row, created, 0) && !invokeFirst(row, new String[] {"addWidget", "addChildWidget"}, created)) {
                    throw new NoSuchMethodException("cannot add the logo widget");
                }
                logo = created;
            }
            if (logo != null) {
                if (file != null) {
                    setIcon(logo, file);
                }
                call(logo, "setVisible", new Class<?>[] {boolean.class}, file != null);
            }
            logoFile = file;
            if (file != null) {
                PluginLog.i("Map credits show " + file.getName() + ".");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            logoFailed = true;
            PluginLog.w("Credit logo cannot be drawn in this ATAK (" + unavailable + "); showing the text credit.");
        }
    }

    /**
     * Calls MarkerIconWidget's one-argument setIcon. Its parameter is ATAK's
     * Icon type, whose package differs between ATAK versions (and there may
     * be overloads), so each candidate's Builder is tried in turn.
     */
    private static void setIcon(Object widget, File file) throws ReflectiveOperationException {
        ReflectiveOperationException last = new NoSuchMethodException("no setIcon on " + widget.getClass().getName());
        for (Method method : widget.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!method.getName().equals("setIcon") || types.length != 1 || types[0].isPrimitive()) {
                continue;
            }
            try {
                method.invoke(widget, icon(types[0], file));
                return;
            } catch (ReflectiveOperationException | IllegalArgumentException candidateFailed) {
                last = candidateFailed instanceof ReflectiveOperationException
                        ? (ReflectiveOperationException) candidateFailed
                        : new NoSuchMethodException(candidateFailed.toString());
            }
        }
        throw last;
    }

    /** An ATAK Icon (via {@code iconType}'s Builder) for a local PNG, {@link #LOGO_HEIGHT_PX} high. */
    private static Object icon(Class<?> iconType, File file) throws ReflectiveOperationException {
        int width = LOGO_HEIGHT_PX * 4;
        int height = LOGO_HEIGHT_PX;
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            width = Math.max(1, Math.round(LOGO_HEIGHT_PX * bounds.outWidth / (float) bounds.outHeight));
        }
        Class<?> builderClass = Class.forName(iconType.getName() + "$Builder", true, iconType.getClassLoader());
        Object builder = builderClass.getConstructor().newInstance();
        call(builder, "setImageUri", new Class<?>[] {int.class, String.class}, 0, "file://" + file.getAbsolutePath());
        call(builder, "setSize", new Class<?>[] {int.class, int.class}, width, height);
        try {
            // Icons are drawn around their anchor, the centre by default, which
            // put half the logo outside its widget; anchor it at the top left.
            call(builder, "setAnchor", new Class<?>[] {int.class, int.class}, 0, 0);
        } catch (NoSuchMethodException noAnchor) {
            PluginLog.w("Icon.Builder has no setAnchor; the logo may be offset.");
        }
        Object built = call(builder, "build", new Class<?>[0]);
        PluginLog.i("Credit logo icon: " + iconType.getName() + ", " + width + "x" + height + " px.");
        return built;
    }

    /** Logs the row's and its layout's position, size and visibility (best effort). */
    private void logPlacement() {
        try {
            Object layout = bottomLayout();
            PluginLog.i("Map credits placement: row " + describe(row) + "; text " + describe(text)
                    + "; bottom layout " + describe(layout) + "; map view " + mapView.getWidth() + "x"
                    + mapView.getHeight() + ".");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            PluginLog.i("Map credits placement unavailable: " + unavailable);
        }
    }

    private static String describe(Object widget) {
        if (widget == null) {
            return "none";
        }
        return "at " + optional(widget, "getPointX") + "," + optional(widget, "getPointY")
                + " size " + optional(widget, "getWidth") + "x" + optional(widget, "getHeight")
                + " visible " + optional(widget, "isVisible");
    }

    private static Object optional(Object target, String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return "?";
        }
    }

    private Object bottomLayout() throws ReflectiveOperationException {
        Object root = mapView.getComponentExtra("rootLayoutWidget");
        if (root == null) {
            throw new IllegalStateException("ATAK has no rootLayoutWidget");
        }
        return call(root, "getLayout", new Class<?>[] {int.class}, BOTTOM_EDGE);
    }

    private static boolean sameFile(File a, File b) {
        return a == null ? b == null : a.equals(b);
    }

    /** {@code addWidgetAt(index, widget)} when the parent has it. */
    private static boolean invokeAt(Object parent, Object widget, int index) throws ReflectiveOperationException {
        for (Method method : parent.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("addWidgetAt") && types.length == 2 && types[0] == int.class
                    && types[1].isInstance(widget)) {
                method.invoke(parent, index, widget);
                return true;
            }
        }
        return false;
    }

    /** Calls the first one-argument method named in {@code names} that accepts {@code argument}. */
    private static boolean invokeFirst(Object target, String[] names, Object argument)
            throws ReflectiveOperationException {
        for (String name : names) {
            for (Method method : target.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0].isInstance(argument)) {
                    method.invoke(target, argument);
                    return true;
                }
            }
        }
        return false;
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... arguments)
            throws ReflectiveOperationException {
        return target.getClass().getMethod(name, types).invoke(target, arguments);
    }
}
