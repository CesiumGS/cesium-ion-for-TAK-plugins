package com.cesium.atak.ion;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapView;
import com.atakmap.app.preferences.ToolsPreferenceFragment;

import com.cesium.tak.ion.http.HttpTransport;
import com.cesium.tak.ion.http.UrlConnectionTransport;
import com.cesium.tak.ion.util.Secret;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gov.tak.api.commons.graphics.Bitmap;
import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Plugin entry point (named in assets/plugin.xml).
 *
 * <p>Lifecycle: the constructor only prepares objects; {@link #onStart()}
 * registers the toolbar button and Tool Preferences and starts the loopback
 * tile proxy; {@link #onStop()} removes both, closes the pane, and stops the
 * proxy and background work, so disabling the plugin in ATAK's Plugin
 * Manager leaves nothing running. Loaded layers stay in ATAK (it persists
 * them) and stream again when the plugin next starts.
 */
public final class CesiumIonPlugin implements IPlugin {
    private static final String TOOL_PREFERENCES_KEY = "cesium_ion_tool_preferences";

    private final Context pluginContext;
    private final Context hostContext;
    private final MapView mapView;
    private final IHostUIService uiService;
    private final ToolbarItem toolbarItem;
    private final SharedPreferences atakPreferences;
    private final SecureTokenStore tokenStore;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener;
    private final IonSession session;
    private final IonSignIn signIn;
    private final ProxyHost proxyHost;
    private final AssetLoader assetLoader;
    private final MapCamera camera;
    private final LoadedAssets loadedAssets;
    private final CreditOverlay creditOverlay;
    /** Redraws the map credits from the tiles the proxy streamed recently. UI thread. */
    private final Runnable creditsRefresher = new Runnable() {
        @Override
        public void run() {
            creditOverlay.show(creditsText(), creditLogo());
            mapView.postDelayed(this, CREDITS_REFRESH_MILLIS);
        }
    };

    private ExecutorService worker;
    private IonPane ionPane;
    private Pane pane;
    private TestCommands testCommands;
    private static final long CREDITS_REFRESH_MILLIS = 3_000;

    public CesiumIonPlugin(IServiceController serviceController) {
        PluginContextProvider contextProvider = serviceController.getService(PluginContextProvider.class);
        if (contextProvider == null) {
            throw new IllegalStateException("ATAK did not provide a plugin context.");
        }
        pluginContext = contextProvider.getPluginContext();
        pluginContext.setTheme(R.style.ATAKPluginTheme);
        mapView = MapView.getMapView();
        if (mapView == null) {
            throw new IllegalStateException("ATAK did not provide its map view.");
        }
        hostContext = mapView.getContext();
        uiService = serviceController.getService(IHostUIService.class);
        atakPreferences = PreferenceManager.getDefaultSharedPreferences(hostContext);
        tokenStore = new SecureTokenStore(hostContext);
        HttpTransport transport = new UrlConnectionTransport();
        signIn = new IonSignIn(hostContext, transport, tokenStore);
        session = new IonSession(() -> {
            IonSettings settings = IonSettings.from(atakPreferences.getAll());
            Secret token = settings.needsToken() ? tokenStore.load(settings.tokenSlot()) : null;
            return settings.openClient(token, transport);
        });
        proxyHost = new ProxyHost(hostContext, session);
        camera = new MapCamera(mapView);
        loadedAssets = new LoadedAssets(hostContext);
        assetLoader = new AssetLoader(new File(hostContext.getFilesDir(), "cesium-ion"), session, proxyHost,
                loadedAssets, transport, camera, mapView::post);
        creditOverlay = new CreditOverlay(mapView);
        settingsListener = (preferences, key) -> {
            if (key != null && IonSettings.isSettingsKey(key)) {
                connectionChanged();
            }
        };

        Drawable icon = pluginContext.getResources().getDrawable(R.drawable.ic_cesium_toolbar, null);
        Bitmap toolbarBitmap = MarshalManager.marshal(icon, Drawable.class, Bitmap.class);
        toolbarItem = new ToolbarItem.Builder(pluginContext.getString(R.string.app_name), toolbarBitmap)
                .setIdentifier(pluginContext.getPackageName())
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                .build();
    }

    @Override
    public void onStart() {
        worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cesium-ion-worker");
            thread.setDaemon(true);
            return thread;
        });
        if (uiService != null) {
            uiService.addToolbarItem(toolbarItem);
        }
        // ATAK 5.7 keeps the registry in com.atakmap.app.preferences.
        ToolsPreferenceFragment.register(new ToolsPreferenceFragment.ToolPreference(
                pluginContext.getString(R.string.prefs_title),
                pluginContext.getString(R.string.prefs_summary),
                TOOL_PREFERENCES_KEY,
                pluginContext.getResources().getDrawable(R.drawable.ic_launcher, null),
                new IonPreferenceFragment(pluginContext, tokenStore)));
        atakPreferences.registerOnSharedPreferenceChangeListener(settingsListener);
        tokenStore.setOnChangeListener(this::connectionChanged);
        if (BuildConfig.DEBUG) {
            testCommands = new TestCommands(hostContext, new TestCommands.Target() {
                @Override
                public void load(long assetId, String name) {
                    pane().load(assetId, name);
                }

                @Override
                public void remove(long assetId) {
                    pane().remove(assetId);
                }

                @Override
                public void removeAll() {
                    pane().removeAll();
                }

                @Override
                public void setBasemapOpacity(int percent) {
                    pane().setBasemapOpacity(percent);
                }

                @Override
                public void flyTo(double latitude, double longitude, double rangeMetres, double tilt,
                        boolean animate) {
                    pane().flyTo(latitude, longitude, rangeMetres, tilt, animate);
                }

                @Override
                public void selectImagery(String layerName) {
                    pane().selectImagery(layerName);
                }

                @Override
                public void loadFirst(String assetType) {
                    pane().loadFirst(assetType);
                }

                @Override
                public void useServer(String serverUrl, String apiUrl) {
                    SharedPreferences.Editor editor = atakPreferences.edit();
                    if (serverUrl == null) {
                        editor.putString(IonSettings.KEY_MODE, IonSettings.Mode.CLOUD.id);
                    } else {
                        String api = apiUrl == null ? "" : apiUrl;
                        editor.putString(IonSettings.KEY_MODE, IonSettings.Mode.SELF_HOSTED.id)
                                .putString(IonSettings.KEY_SERVER_URL, serverUrl)
                                .putString(IonSettings.KEY_API_URL, api)
                                .putBoolean(IonSettings.KEY_ALLOW_HTTP,
                                        serverUrl.startsWith("http:") || api.startsWith("http:"))
                                .putBoolean(IonSettings.KEY_SINGLE_USER, true);
                    }
                    editor.apply();
                    PluginLog.i("Test command: using " + IonSettings.from(atakPreferences.getAll()).describe() + ".");
                }

                @Override
                public void showPane(boolean visible) {
                    if (visible) {
                        CesiumIonPlugin.this.showPane();
                    } else if (pane != null && uiService.isPaneVisible(pane)) {
                        uiService.closePane(pane);
                    }
                }

                @Override
                public void signIn() {
                    // Opens ion's sign-in page only; the person signs in (test commands never set tokens).
                    pane().startSignIn();
                }

                @Override
                public void showSettings(boolean open) {
                    pane().showSettings(open);
                }

                @Override
                public void selectWorkspace(String which) {
                    pane().selectWorkspaceForTest("first".equals(which));
                }

                @Override
                public void logCamera(String label) {
                    PluginLog.i("Camera" + (label.isEmpty() ? "" : " " + label) + ": " + camera.describe());
                }

                @Override
                public void quitAtak() {
                    // ATAK clears plugin auto-load after an unclean exit (force-stop,
                    // reinstall); finishing its activity runs its clean shutdown.
                    PluginLog.i("Test command: closing ATAK cleanly.");
                    if (hostContext instanceof Activity) {
                        ((Activity) hostContext).finish();
                    } else {
                        PluginLog.w("ATAK's map context is not an activity; cannot close it.");
                    }
                }
            });
            testCommands.register();
        }
        worker.execute(() -> {
            try {
                proxyHost.start();
                assetLoader.registerPersisted();
            } catch (IOException | RuntimeException failure) {
                PluginLog.e("The tile proxy could not start; ion imagery and 3D Tiles will not stream.", failure);
            }
        });
        mapView.postDelayed(creditsRefresher, CREDITS_REFRESH_MILLIS);
        PluginLog.i("Cesium ion plugin started.");
    }

    /** The logo of the first loaded 3D Tiles layer that has one (Google's), or null. */
    private File creditLogo() {
        for (LoadedAssets.Entry entry : loadedAssets.all()) {
            File logo = entry.kind == LoadedAssets.Kind.TILES_3D ? assetLoader.creditLogo(entry.assetId) : null;
            if (logo != null) {
                return logo;
            }
        }
        return null;
    }

    /**
     * "Cesium ion | " and the per-tile credits of each loaded 3D Tiles
     * layer (Google's start with "Google"), or "" when no layer has any.
     */
    private String creditsText() {
        StringBuilder credits = new StringBuilder();
        for (LoadedAssets.Entry entry : loadedAssets.all()) {
            if (entry.kind != LoadedAssets.Kind.TILES_3D) {
                continue;
            }
            String line = proxyHost.creditLine(entry.assetId);
            if (!line.isEmpty()) {
                credits.append(credits.length() == 0 ? "Cesium ion | " : " | ").append(line);
            }
        }
        return credits.toString();
    }

    @Override
    public void onStop() {
        atakPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener);
        tokenStore.setOnChangeListener(null);
        if (testCommands != null) {
            testCommands.unregister();
            testCommands = null;
        }
        ToolsPreferenceFragment.unregister(TOOL_PREFERENCES_KEY);
        if (uiService != null) {
            uiService.removeToolbarItem(toolbarItem);
            if (pane != null && uiService.isPaneVisible(pane)) {
                uiService.closePane(pane);
            }
        }
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        mapView.removeCallbacks(creditsRefresher);
        creditOverlay.dispose();
        assetLoader.detachAll();
        proxyHost.stop();
        session.reset();
        pane = null;
        ionPane = null;
        PluginLog.i("Cesium ion plugin stopped.");
    }

    /** The server, mode, or token changed: reconnect on next use. Main thread. */
    private void connectionChanged() {
        session.reset();
        if (ionPane != null) {
            ionPane.connectionChanged();
        }
    }

    /** The pane's content, built without showing it (test commands use it too). Main thread. */
    private IonPane pane() {
        if (ionPane == null) {
            ionPane = new IonPane(hostContext, mapView, atakPreferences, tokenStore, session, assetLoader, worker,
                    signIn, pluginContext.getResources().getDrawable(R.drawable.cesium_ion_logo, null));
            ionPane.view();
        }
        return ionPane;
    }

    private void showPane() {
        if (uiService == null || worker == null) {
            return;
        }
        if (pane == null) {
            pane = new PaneBuilder(pane().view())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.6D)
                    .build();
        } else {
            ionPane.refreshStatus();
        }
        if (!uiService.isPaneVisible(pane)) {
            uiService.showPane(pane, null);
        }
    }
}
