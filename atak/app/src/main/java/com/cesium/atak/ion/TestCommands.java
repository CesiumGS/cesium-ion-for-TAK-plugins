package com.cesium.atak.ion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * Debug builds only: lets {@code atak/scripts/device-test.sh --scenario}
 * drive the plugin over adb, so device tests run without tapping.
 *
 * <pre>adb shell am broadcast -a com.cesium.atak.ion.TEST -p com.atakmap.app.civ --es cmd load --el asset 1</pre>
 *
 * <p>Security: never registered in release builds, and senders must hold
 * {@code android.permission.DUMP}, which the adb shell and the system have
 * and ordinary apps cannot obtain. Commands only do what the pane's buttons
 * do; no command reveals the token.
 */
final class TestCommands extends BroadcastReceiver {
    static final String ACTION = "com.cesium.atak.ion.TEST";
    private static final String SENDER_PERMISSION = "android.permission.DUMP";

    /** What the commands act on: the pane's own actions. */
    interface Target {
        void load(long assetId, String name);

        void remove(long assetId);

        void removeAll();

        void setBasemapOpacity(int percent);

        void flyTo(double latitude, double longitude, double rangeMetres, double tilt, boolean animate);

        void selectImagery(String layerName);

        void loadFirst(String assetType);

        /**
         * Points the plugin at ion Cloud ({@code apiUrl} null) or at a
         * single-user Self-Hosted server. Non-secret settings only: tokens are
         * never set by test commands.
         */
        void useServer(String serverUrl, String apiUrl);

        /** Opens the Cesium ion pane, as the toolbar button does, or closes it. */
        void showPane(boolean visible);

        /** Starts "Sign in with Cesium ion": opens ion's page in the browser. Never signs out. */
        void signIn();

        /** Opens or closes the pane's settings panel (screenshots of the UI). */
        void showSettings(boolean open);

        /** Selects the account's first team workspace ({@code first}) or the personal one. */
        void selectWorkspace(String which);

        /** Logs where the map is looking, so a report shows whether a fly took effect. */
        void logCamera(String label);

        /** Closes ATAK's activity so it records a clean shutdown and reloads plugins at next start. */
        void quitAtak();
    }

    private final Context hostContext;
    private final Target target;
    private boolean registered;

    TestCommands(Context hostContext, Target target) {
        this.hostContext = hostContext;
        this.target = target;
    }

    void register() {
        if (!BuildConfig.DEBUG || registered) {
            return;
        }
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            hostContext.registerReceiver(this, filter, SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED);
        } else {
            hostContext.registerReceiver(this, filter, SENDER_PERMISSION, null);
        }
        registered = true;
        PluginLog.i("Test commands enabled (debug build; adb shell only).");
    }

    void unregister() {
        if (registered) {
            hostContext.unregisterReceiver(this);
            registered = false;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String command = intent.getStringExtra("cmd");
        String step = intent.getStringExtra("step");
        PluginLog.i("TEST " + (step == null ? "" : step + " ") + "command: " + command);
        try {
            if (command == null) {
                throw new IllegalArgumentException("missing cmd");
            }
            switch (command) {
                case "load":
                    long assetId = intent.getLongExtra("asset", -1);
                    if (assetId <= 0) {
                        throw new IllegalArgumentException("load needs --el asset <id>");
                    }
                    String name = intent.getStringExtra("name");
                    target.load(assetId, name == null ? "Asset " + assetId : name);
                    break;
                case "remove":
                    target.remove(intent.getLongExtra("asset", -1));
                    break;
                case "removeAll":
                    target.removeAll();
                    break;
                case "basemap":
                    target.setBasemapOpacity(intent.getIntExtra("percent", 100));
                    break;
                case "fly":
                    target.flyTo(number(intent, "lat"), number(intent, "lon"), number(intent, "range"),
                            intent.hasExtra("tilt") ? number(intent, "tilt") : 0,
                            !"jump".equals(intent.getStringExtra("motion")));
                    break;
                case "select":
                    String layer = intent.getStringExtra("layer");
                    if (layer == null) {
                        throw new IllegalArgumentException("select needs --es layer <name>");
                    }
                    target.selectImagery(layer);
                    break;
                case "loadFirst":
                    String type = intent.getStringExtra("type");
                    if (type == null) {
                        throw new IllegalArgumentException("loadFirst needs --es type <TILES_3D|IMAGERY|TERRAIN|GEOJSON|KML>");
                    }
                    target.loadFirst(type);
                    break;
                case "server":
                    String url = intent.getStringExtra("url");
                    target.useServer(url == null || url.equals("cloud") ? null : url, intent.getStringExtra("api"));
                    break;
                case "pane":
                    target.showPane(!"close".equals(intent.getStringExtra("state")));
                    break;
                case "signIn":
                    target.signIn();
                    break;
                case "settings":
                    target.showSettings(!"close".equals(intent.getStringExtra("state")));
                    break;
                case "workspace":
                    target.selectWorkspace(intent.getStringExtra("which"));
                    break;
                case "camera":
                    String label = intent.getStringExtra("label");
                    target.logCamera(label == null ? "" : label);
                    break;
                case "quit":
                    target.quitAtak();
                    break;
                default:
                    throw new IllegalArgumentException("unknown cmd " + command);
            }
        } catch (RuntimeException failure) {
            PluginLog.w("TEST command failed: " + failure.getMessage());
        }
    }

    /** Doubles arrive as strings ({@code --es lat 46.85}) so the shell needs no float extras. */
    private static double number(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        if (value == null) {
            throw new IllegalArgumentException("missing --es " + key);
        }
        return Double.parseDouble(value);
    }
}
