package com.cesium.atak.ion;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.cesium.tak.ion.AssetQuery;
import com.cesium.tak.ion.ConnectionCheck;
import com.cesium.tak.ion.IonClient;
import com.cesium.tak.ion.IonException;
import com.cesium.tak.ion.model.AssetPage;
import com.cesium.tak.ion.model.AssetStatus;
import com.cesium.tak.ion.model.AssetType;
import com.cesium.tak.ion.model.IonAsset;
import com.cesium.tak.ion.model.PageCursor;
import com.cesium.tak.ion.model.Team;
import com.cesium.tak.ion.tiles.TilesetBounds;
import com.cesium.tak.ion.util.Redactor;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * The Cesium ion pane: connection status and settings, what is on the map,
 * the asset browser (search, type filters, add, remove), ATAK basemap and
 * camera controls, and an activity log behind a toggle. All network work
 * runs on {@code worker}; views change on the UI thread.
 */
final class IonPane {
    static final long GOOGLE_PHOTOREALISTIC_ASSET_ID = 2275207L;

    /** A global layer every Cesium ion Cloud account can add. */
    private static final class Featured {
        final long assetId;
        final String name;
        final AssetType type;
        final String detail;

        Featured(long assetId, String name, AssetType type, String detail) {
            this.assetId = assetId;
            this.name = name;
            this.type = type;
            this.detail = detail;
        }
    }

    private static final Featured[] FEATURED = {
        new Featured(GOOGLE_PHOTOREALISTIC_ASSET_ID, "Google Photorealistic 3D Tiles", AssetType.TILES_3D,
                "Photorealistic cities worldwide. Hides the ATAK basemap while shown"),
        new Featured(1L, "Cesium World Terrain", AssetType.TERRAIN, "Global high-resolution elevation"),
        new Featured(96188L, "Cesium OSM Buildings", AssetType.TILES_3D, "Buildings worldwide from OpenStreetMap"),
    };
    /** The newest lines are shown in the pane; logcat keeps everything. */
    private static final int MAX_LOG_LINES = 40;
    private static final int PAGE_SIZE = 50;

    /** The asset list's type filter. */
    private enum Filter {
        ALL("All"), TILES("3D Tiles"), IMAGERY("Imagery"), TERRAIN("Terrain"), VECTOR("Vector");

        final String label;

        Filter(String label) {
            this.label = label;
        }

        boolean matches(AssetType type) {
            switch (this) {
                case TILES:
                    return type == AssetType.TILES_3D;
                case IMAGERY:
                    return type == AssetType.IMAGERY;
                case TERRAIN:
                    return type == AssetType.TERRAIN;
                case VECTOR:
                    return type == AssetType.GEOJSON || type == AssetType.KML;
                default:
                    return true;
            }
        }
    }

    /** What the last request to the server showed. */
    private enum Connection { UNKNOWN, CHECKING, CONNECTED, FAILED }

    private final Context hostContext;
    private final MapView mapView;
    private final SharedPreferences atakPreferences;
    private final SecureTokenStore tokens;
    private final IonSession session;
    private final AssetLoader loader;
    private final ExecutorService worker;
    private final PaneStyle style;
    private final MapCamera camera;
    private final Deque<String> logLines = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private LinearLayout root;
    private ScrollView container;
    private final Drawable logo;
    private TextView connectionState;
    private TextView serverName;
    private TextView connectionDetail;
    private TextView problem;
    private TextView lastActivity;
    private android.widget.ImageButton settingsGear;
    /** Account, server and token: opened from the gear next to the status. */
    private LinearLayout settingsPanel;
    /** Shown in the main pane while not signed in. */
    private LinearLayout signInCard;
    private TextView signInCardText;
    private Button signInCta;
    private Button signInButton;
    private final IonSignIn signIn;
    private Button testButton;
    private PaneSettingsForm settingsForm;
    private View settingsView;
    private TextView log;
    private Button logToggle;
    private final BasemapBlend basemapBlend = new BasemapBlend();
    private TextView basemapValue;
    private SeekBar basemapOpacity;

    private EditText search;
    private Button removeAllButton;
    private TextView loadedHeading;
    private TextView loadedHint;
    private LinearLayout loadedList;
    private LinearLayout featuredSection;
    private LinearLayout featuredList;
    /** Featured rows shown (those not already in the asset list). UI thread only. */
    private int featuredShown;
    private TextView assetsHeading;
    private LinearLayout filterRow;
    private LinearLayout assetList;
    private TextView assetSummary;
    private Button loadMore;
    /** UI thread only. */
    private final List<IonAsset> assets = new ArrayList<>();
    /** Assets being added or removed; their buttons show progress. UI thread only. */
    private final Set<Long> pending = new HashSet<>();
    private Filter filter = Filter.ALL;
    private PageCursor nextPage;
    private String currentSearch;
    private volatile Connection connection = Connection.UNKNOWN;
    private volatile String connectionProblem = "";
    /** The signed-in ion username ({@code null} not known yet, empty if ion does not say). */
    private volatile String accountName;
    /** The server and token slot {@link #accountName} belongs to. */
    private volatile String accountFor;
    /** The account's team workspaces (empty: personal only, or the server has no teams). */
    private volatile List<Team> teams = Collections.emptyList();
    private LinearLayout workspaceSection;
    private LinearLayout workspaceChips;

    IonPane(Context hostContext, MapView mapView, SharedPreferences atakPreferences, SecureTokenStore tokens,
            IonSession session, AssetLoader loader, ExecutorService worker, IonSignIn signIn, Drawable logo) {
        this.signIn = signIn;
        this.logo = logo;
        this.hostContext = hostContext;
        this.mapView = mapView;
        this.atakPreferences = atakPreferences;
        this.tokens = tokens;
        this.session = session;
        this.loader = loader;
        this.worker = worker;
        this.style = new PaneStyle(hostContext);
        this.camera = new MapCamera(mapView);
    }

    View view() {
        if (root == null) {
            build();
        }
        refreshStatus();
        return container;
    }

    /** Re-reads settings; call when the pane is shown or preferences change. UI thread. */
    void refreshStatus() {
        if (connectionState == null) {
            return;
        }
        IonSettings settings = currentSettings();
        String label;
        int color;
        boolean saved = tokens.has(settings.tokenSlot());
        if (settings.needsToken() && !saved) {
            label = signIn.isRunning() ? "Signing in…" : "Not signed in";
            color = PaneStyle.WARNING;
        } else {
            switch (connection) {
                case CONNECTED:
                    label = "Connected";
                    color = PaneStyle.SUCCESS;
                    break;
                case FAILED:
                    label = "Not connected";
                    color = PaneStyle.DANGER;
                    break;
                case CHECKING:
                    label = "Connecting…";
                    color = PaneStyle.MUTED;
                    break;
                default:
                    label = "Ready";
                    color = PaneStyle.MUTED;
            }
        }
        style.setStatus(connectionState, label, color);
        // Hover (mouse or stylus) or long-press shows who and what the pane is connected to.
        String identity = identity(settings, saved);
        connectionState.setTooltipText(identity);
        connectionState.setContentDescription(label + ". " + identity + " Opens settings.");
        serverName.setText(settings.describe());
        String name = accountName;
        String auth = !settings.needsToken() ? "No sign-in needed on this server."
                : saved ? (name != null && !name.isEmpty() ? "Signed in as " + name + ". " : "Signed in. ")
                        + "Your access is saved on this device."
                : signIn.isRunning() ? "Finish signing in in the browser, then come back here."
                : IonSignIn.isAvailable() ? "Sign in with your Cesium ion account to see your assets here."
                : "Paste an access token below (scopes assets:list and assets:read).";
        connectionDetail.setText(auth);
        boolean needsSignIn = settings.needsToken() && !saved;
        signInCard.setVisibility(needsSignIn ? View.VISIBLE : View.GONE);
        if (needsSignIn) {
            signInCardText.setText("Sign in to " + settings.describe()
                    + " to see your assets here and add them to the map.");
            signInCta.setText(signIn.isRunning() ? "Cancel sign-in"
                    : IonSignIn.isAvailable() ? "Sign in with Cesium ion" : "Add an access token");
            style.styleButton(signInCta, signIn.isRunning() ? PaneStyle.ButtonKind.SECONDARY : PaneStyle.ButtonKind.PRIMARY);
        }
        boolean failed = connection == Connection.FAILED && !connectionProblem.isEmpty();
        problem.setText(failed ? connectionProblem : "");
        problem.setVisibility(failed ? View.VISIBLE : View.GONE);
        if (signInButton != null) {
            boolean offer = settings.needsToken() && (saved || IonSignIn.isAvailable());
            signInButton.setVisibility(offer ? View.VISIBLE : View.GONE);
            if (signIn.isRunning()) {
                signInButton.setText("Cancel sign-in");
                style.styleButton(signInButton, PaneStyle.ButtonKind.SECONDARY);
            } else if (saved) {
                signInButton.setText("Sign out");
                style.styleButton(signInButton, PaneStyle.ButtonKind.SECONDARY);
            } else {
                signInButton.setText("Sign in with Cesium ion");
                style.styleButton(signInButton, PaneStyle.ButtonKind.PRIMARY);
            }
        }
        if (testButton != null) {
            testButton.setVisibility(isConfigured() ? View.VISIBLE : View.GONE);
        }
        renderWorkspace(settings);
        updateFeaturedVisibility();
    }

    /** Who and what the pane is connected to, for the status pill's tooltip. */
    private String identity(IonSettings settings, boolean saved) {
        String where = settings.describe();
        String name = accountName;
        String text;
        if (!settings.needsToken()) {
            text = connection == Connection.FAILED ? "Cannot reach " + where + "." : "Connected to " + where + ". No sign-in needed.";
        } else if (!saved) {
            text = (signIn.isRunning() ? "Signing in to " : "Not signed in to ") + where + ".";
        } else if (name != null && !name.isEmpty()) {
            text = "Signed in to " + where + " as " + name + ".";
        } else {
            text = "Signed in to " + where + ".";
        }
        if (!settings.teamId.isEmpty()) {
            text += " Team: " + settings.describeWorkspace() + ".";
        }
        if (connection == Connection.FAILED && !connectionProblem.isEmpty()) {
            text += "\n" + connectionProblem;
        }
        return text;
    }

    /** The server or token changed: forget the old connection state and list the new server's assets. */
    void connectionChanged() {
        if (root == null) {
            return;
        }
        root.post(() -> {
            connection = Connection.UNKNOWN;
            connectionProblem = "";
            accountFor = null;
            accountName = null;
            teams = Collections.emptyList();
            refreshStatus();
            if (isConfigured()) {
                searchAssets();
            } else {
                assets.clear();
                nextPage = null;
                renderFilters();
                renderAssets();
            }
            renderLoaded();
        });
    }

    private boolean isConfigured() {
        IonSettings settings = currentSettings();
        return !settings.needsToken() || tokens.has(settings.tokenSlot());
    }

    private void build() {
        root = style.vertical();
        int gutter = style.dp(16);
        root.setPadding(gutter, style.dp(14), gutter, style.dp(28));
        root.setBackgroundColor(PaneStyle.BACKGROUND);

        buildHeader();
        buildLoaded();
        buildFeatured();
        buildAssetBrowser();
        buildMapControls();
        buildActivity();

        // One scrolling column fits any pane size.
        container = new ScrollView(hostContext);
        container.setBackgroundColor(PaneStyle.BACKGROUND);
        container.setFillViewport(true);
        container.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        renderLoaded();
        renderFilters();
        if (isConfigured()) {
            searchAssets();
        } else {
            renderAssets();
        }
    }

    /**
     * The Cesium ion wordmark, the connection pill (hover or long-press: who is
     * signed in, or which server) and the settings gear; then the settings panel
     * (closed by default) and, while not signed in, a sign-in card.
     */
    private void buildHeader() {
        LinearLayout titleRow = style.horizontal();
        titleRow.setMinimumHeight(style.dp(44));
        if (logo != null) {
            ImageView mark = new ImageView(hostContext);
            mark.setImageDrawable(logo);
            mark.setAdjustViewBounds(true);
            mark.setScaleType(ImageView.ScaleType.FIT_START);
            mark.setContentDescription("Cesium ion");
            titleRow.addView(mark, new LinearLayout.LayoutParams(0, style.dp(26), 1f));
        } else {
            titleRow.addView(style.bold("Cesium ion", 22),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        connectionState = style.statusPill("", PaneStyle.MUTED);
        connectionState.setClickable(true);
        connectionState.setOnClickListener(v -> showSettings(true));
        titleRow.addView(connectionState);
        settingsGear = style.iconButton(new PaneStyle.GearGlyph(PaneStyle.TEXT), "Settings", v -> toggleSettings());
        titleRow.addView(settingsGear, PaneStyle.leftMargin(style.dp(8)));
        root.addView(titleRow);

        buildSettingsPanel();
        buildSignInCard();
    }

    /** Account, server and token, in one card under the header. */
    private void buildSettingsPanel() {
        settingsPanel = style.card();
        settingsPanel.setPadding(style.dp(16), style.dp(12), style.dp(16), style.dp(14));
        LinearLayout titleRow = style.horizontal();
        titleRow.addView(style.medium("Settings", 16, PaneStyle.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(style.button("Done", PaneStyle.ButtonKind.TEXT, v -> showSettings(false)));
        settingsPanel.addView(titleRow);

        serverName = style.medium("", 15, PaneStyle.TEXT);
        serverName.setPadding(0, style.dp(4), 0, 0);
        settingsPanel.addView(serverName);
        connectionDetail = style.text("", 13, PaneStyle.MUTED);
        connectionDetail.setPadding(0, style.dp(2), 0, 0);
        connectionDetail.setLineSpacing(style.dp(2), 1f);
        settingsPanel.addView(connectionDetail);
        problem = style.text("", 13, PaneStyle.DANGER);
        problem.setPadding(0, style.dp(6), 0, 0);
        problem.setVisibility(View.GONE);
        settingsPanel.addView(problem);

        LinearLayout actions = style.horizontal();
        actions.setPadding(0, style.dp(12), 0, style.dp(4));
        signInButton = style.button("Sign in with Cesium ion", PaneStyle.ButtonKind.PRIMARY, v -> signInOrOut());
        actions.addView(signInButton, PaneStyle.leftMargin(0));
        testButton = style.button("Test connection", PaneStyle.ButtonKind.TEXT, v -> testConnection());
        actions.addView(testButton, PaneStyle.leftMargin(style.dp(4)));
        HorizontalScrollView actionScroll = new HorizontalScrollView(hostContext);
        actionScroll.setHorizontalScrollBarEnabled(false);
        actionScroll.addView(actions);
        settingsPanel.addView(actionScroll);

        // Team workspaces, when the account has any: whose assets the pane lists.
        workspaceSection = style.vertical();
        workspaceSection.setPadding(0, style.dp(10), 0, style.dp(4));
        workspaceSection.addView(style.medium("Workspace", 14, PaneStyle.TEXT));
        TextView workspaceHint = style.text("Whose Cesium ion assets this pane lists and adds.", 12, PaneStyle.MUTED);
        workspaceHint.setPadding(0, style.dp(2), 0, style.dp(6));
        workspaceSection.addView(workspaceHint);
        workspaceChips = style.horizontal();
        HorizontalScrollView chipScroll = new HorizontalScrollView(hostContext);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(workspaceChips);
        workspaceSection.addView(chipScroll);
        workspaceSection.setVisibility(View.GONE);
        settingsPanel.addView(workspaceSection);

        // Server (Cloud or Self-Hosted) and, where sign-in is not available, a pasted token.
        settingsForm = new PaneSettingsForm(hostContext, style, atakPreferences, tokens, this::append,
                this::settingsSaved);
        settingsView = settingsForm.view();
        settingsPanel.addView(settingsView);

        // Opens by itself only when there is no way to sign in (a token must be pasted).
        settingsPanel.setVisibility(!isConfigured() && !IonSignIn.isAvailable() ? View.VISIBLE : View.GONE);
        settingsGear.setSelected(settingsPanel.getVisibility() == View.VISIBLE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = style.dp(10);
        root.addView(settingsPanel, params);
    }

    /** First run: what signing in gives, and the button; server choice lives in settings. */
    private void buildSignInCard() {
        signInCard = style.card();
        signInCard.setPadding(style.dp(16), style.dp(14), style.dp(16), style.dp(14));
        signInCard.addView(style.medium("Sign in to Cesium ion", 16, PaneStyle.TEXT));
        signInCardText = style.text("", 13, PaneStyle.MUTED);
        signInCardText.setPadding(0, style.dp(4), 0, 0);
        signInCardText.setLineSpacing(style.dp(2), 1f);
        signInCard.addView(signInCardText);
        LinearLayout actions = style.horizontal();
        actions.setPadding(0, style.dp(12), 0, 0);
        signInCta = style.button("Sign in with Cesium ion", PaneStyle.ButtonKind.PRIMARY, v -> {
            if (signIn.isRunning()) {
                signIn.cancel();
            } else {
                startSignIn();
            }
        });
        actions.addView(signInCta, PaneStyle.leftMargin(0));
        actions.addView(style.button("Server settings", PaneStyle.ButtonKind.TEXT, v -> showSettings(true)),
                PaneStyle.leftMargin(style.dp(4)));
        signInCard.addView(actions);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = style.dp(10);
        root.addView(signInCard, params);
    }

    /** What the plugin has put on the map, and where each layer lives in ATAK. */
    private void buildLoaded() {
        removeAllButton = style.button("Remove all", PaneStyle.ButtonKind.TEXT, v -> removeAll());
        loadedHeading = style.heading("On your map");
        LinearLayout section = style.section(loadedHeading, removeAllButton);
        loadedHint = style.text("To hide a layer, use the ATAK menu shown under it. Remove takes it off "
                + "the map.", 13, PaneStyle.MUTED);
        loadedHint.setPadding(0, 0, 0, style.dp(4));
        section.addView(loadedHint);
        loadedList = style.vertical();
        section.addView(loadedList);
        root.addView(section);
    }

    private void buildFeatured() {
        featuredSection = style.section(style.heading("From Cesium"), null);
        TextView about = style.text("Global layers any Cesium ion account can add.", 13, PaneStyle.MUTED);
        about.setPadding(0, 0, 0, style.dp(4));
        featuredSection.addView(about);
        featuredList = style.vertical();
        featuredSection.addView(featuredList);
        root.addView(featuredSection);
    }

    private void buildAssetBrowser() {
        Button refresh = style.button("Refresh", PaneStyle.ButtonKind.TEXT, v -> searchAssets());
        assetsHeading = style.heading("Your assets");
        LinearLayout section = style.section(assetsHeading, refresh);

        LinearLayout searchRow = style.horizontal();
        searchRow.setPadding(0, 0, 0, style.dp(10));
        search = style.field("Search your assets", 0);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                searchAssets();
                return true;
            }
            return false;
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchRow.addView(style.button("Search", PaneStyle.ButtonKind.SECONDARY, v -> searchAssets()),
                PaneStyle.leftMargin(style.dp(8)));
        section.addView(searchRow);

        HorizontalScrollView filters = new HorizontalScrollView(hostContext);
        filters.setHorizontalScrollBarEnabled(false);
        filterRow = style.horizontal();
        filters.addView(filterRow);
        section.addView(filters);

        assetSummary = style.text("", 13, PaneStyle.MUTED);
        assetSummary.setPadding(0, style.dp(10), 0, style.dp(2));
        section.addView(assetSummary);
        assetList = style.vertical();
        section.addView(assetList);
        loadMore = style.button("Show more assets", PaneStyle.ButtonKind.SECONDARY, v -> fetchPage(false));
        loadMore.setVisibility(View.GONE);
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        moreParams.topMargin = style.dp(8);
        section.addView(loadMore, moreParams);
        root.addView(section);
    }

    private void buildMapControls() {
        LinearLayout section = style.section(style.heading("Map display"), null);
        // ATAK's own basemap is draped on its (coarser) terrain and cuts
        // through photorealistic ground; fading it is the fix.
        LinearLayout label = style.horizontal();
        label.addView(style.text("ATAK basemap", 14, PaneStyle.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        basemapValue = style.medium("100%", 14, PaneStyle.TEXT);
        label.addView(basemapValue);
        section.addView(label);
        TextView why = style.text("Fade it so 3D Tiles and photorealistic cities show through.", 12,
                PaneStyle.MUTED);
        section.addView(why);
        basemapOpacity = new SeekBar(hostContext);
        basemapOpacity.setMax(100);
        basemapOpacity.setProgress(100);
        basemapOpacity.setPadding(style.dp(8), style.dp(14), style.dp(8), style.dp(14));
        basemapOpacity.setProgressTintList(android.content.res.ColorStateList.valueOf(PaneStyle.ACCENT));
        basemapOpacity.setThumbTintList(android.content.res.ColorStateList.valueOf(PaneStyle.ACCENT));
        basemapOpacity.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(PaneStyle.BORDER));
        basemapOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                basemapValue.setText(progress + "%");
                if (fromUser) {
                    applyBasemapOpacity(progress, false);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                applyBasemapOpacity(seekBar.getProgress(), true);
            }
        });
        section.addView(basemapOpacity);
        LinearLayout buttons = style.horizontal();
        buttons.addView(style.button("Hide basemap", PaneStyle.ButtonKind.SECONDARY, v -> setBasemapOpacity(0)));
        buttons.addView(style.button("Show basemap", PaneStyle.ButtonKind.SECONDARY, v -> setBasemapOpacity(100)),
                PaneStyle.leftMargin(style.dp(8)));
        section.addView(buttons);
        TextView gestures = style.text("Rotate with a two-finger twist; tilt by dragging two fingers up or down.",
                12, PaneStyle.MUTED);
        gestures.setPadding(0, style.dp(14), 0, style.dp(4));
        section.addView(gestures);
        section.addView(style.button("Turn on rotate and tilt", PaneStyle.ButtonKind.SECONDARY,
                v -> enableGestures()));
        root.addView(section);
    }

    private void buildActivity() {
        logToggle = style.button("Show", PaneStyle.ButtonKind.TEXT, v -> toggleLog());
        LinearLayout section = style.section(style.heading("Activity"), logToggle);
        lastActivity = style.text("", 12, PaneStyle.MUTED);
        lastActivity.setSingleLine(true);
        lastActivity.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lastActivity.setVisibility(View.GONE);
        section.addView(lastActivity);
        log = style.text("", 12, PaneStyle.MUTED);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setVisibility(View.GONE);
        section.addView(log);
        root.addView(section);
    }

    private void toggleLog() {
        boolean show = log.getVisibility() != View.VISIBLE;
        log.setVisibility(show ? View.VISIBLE : View.GONE);
        logToggle.setText(show ? "Hide" : "Show");
    }

    // ---- Asset browser -------------------------------------------------

    private void searchAssets() {
        String query = search.getText().toString().trim();
        currentSearch = query.isEmpty() ? null : query;
        fetchPage(true);
    }

    /** Fetches the first page ({@code fresh}) or the next one. UI thread. */
    private void fetchPage(boolean fresh) {
        String query = currentSearch;
        PageCursor cursor = fresh ? null : nextPage;
        if (!fresh && cursor == null) {
            return;
        }
        loadMore.setEnabled(false);
        if (fresh) {
            assetSummary.setText("Loading your assets…");
        }
        if (connection != Connection.CONNECTED) {
            connection = Connection.CHECKING;
            refreshStatus();
        }
        run(fresh ? "List assets" + (query == null ? "" : " matching \"" + query + "\"") : "Load more assets", () -> {
            AssetQuery request = AssetQuery.all().withLimit(PAGE_SIZE);
            if (query != null) {
                request = request.withSearch(query);
            }
            if (cursor != null) {
                request = request.after(cursor);
            }
            AssetPage page;
            try {
                page = session.client().listAssets(request);
                connected();
            } catch (IonException failure) {
                disconnected(failure);
                root.post(() -> assetSummary.setText("Could not list your assets. Tap the status at the top for details."));
                throw failure;
            } finally {
                root.post(() -> loadMore.setEnabled(true));
            }
            append("Found " + page.items().size() + " assets" + (page.hasNext() ? " (more available)." : "."));
            root.post(() -> {
                if (fresh) {
                    assets.clear();
                }
                assets.addAll(page.items());
                nextPage = page.next();
                renderFilters();
                renderAssets();
                renderLoaded();
            });
        });
    }

    /** Worker thread. Also looks up the signed-in username once per server and token. */
    private void connected() {
        connection = Connection.CONNECTED;
        connectionProblem = "";
        IonSettings settings = currentSettings();
        String key = settings.tokenSlot() + "|" + settings.describe();
        if (!settings.needsToken() || !tokens.has(settings.tokenSlot()) || key.equals(accountFor)) {
            return;
        }
        accountFor = key;
        try {
            String name = session.client().me().username();
            accountName = name;
            if (!name.isEmpty()) {
                append("Signed in as " + name + ".");
            }
        } catch (IonException failure) {
            // Access saved before sign-in asked for profile:read cannot read the name.
            accountName = "";
        }
        try {
            // Empty when the server or account has no teams (the picker stays hidden).
            List<Team> found = session.client().teams();
            teams = found;
            append(found.isEmpty() ? "Workspace: personal (no teams on this account)."
                    : "Workspaces: personal and " + found.size() + (found.size() == 1 ? " team." : " teams."));
        } catch (IonException failure) {
            teams = Collections.emptyList();
            PluginLog.w("Listing teams failed (" + failure.kind() + ")");
        }
    }

    /** The workspace chips (Personal and each team); hidden when there are no teams. UI thread. */
    private void renderWorkspace(IonSettings settings) {
        List<Team> current = teams;
        boolean show = !current.isEmpty() || !settings.teamId.isEmpty();
        workspaceSection.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            return;
        }
        workspaceChips.removeAllViews();
        workspaceChips.addView(style.chip("Personal", settings.teamId.isEmpty(), v -> selectWorkspace(null)));
        boolean listed = false;
        for (Team team : current) {
            listed |= team.id().equals(settings.teamId);
            workspaceChips.addView(style.chip(team.name(), team.id().equals(settings.teamId), v -> selectWorkspace(team)));
        }
        if (!listed && !settings.teamId.isEmpty()) {
            // Saved, but not (yet) listed: keep it visible and selected.
            workspaceChips.addView(style.chip(settings.describeWorkspace(), true, v -> { }));
        }
    }

    /** Test command: the first listed team, or the personal workspace. UI thread. */
    void selectWorkspaceForTest(boolean firstTeam) {
        List<Team> current = teams;
        if (firstTeam && current.isEmpty()) {
            append("No team workspaces to select.");
            return;
        }
        selectWorkspace(firstTeam ? current.get(0) : null);
    }

    /** Saves the workspace ({@code null}: personal); the preference listener reconnects. UI thread. */
    private void selectWorkspace(Team team) {
        IonSettings settings = currentSettings();
        String id = team == null ? "" : team.id();
        if (id.equals(settings.teamId)) {
            return;
        }
        SharedPreferences.Editor editor = atakPreferences.edit();
        if (team == null) {
            editor.remove(settings.teamKey()).remove(settings.teamNameKey());
        } else {
            editor.putString(settings.teamNameKey(), team.name()).putString(settings.teamKey(), team.id());
        }
        editor.apply();
        append("Workspace: " + (team == null ? "Personal" : team.name()) + ". Listing its assets.");
    }

    private void disconnected(IonException failure) {
        connection = Connection.FAILED;
        connectionProblem = Redactor.redact(failure.getMessage() == null ? failure.kind().toString()
                : failure.getMessage());
    }

    /** The type filter chips, with counts of the listed assets. UI thread. */
    private void renderFilters() {
        filterRow.removeAllViews();
        for (Filter each : Filter.values()) {
            int count = 0;
            for (IonAsset asset : assets) {
                if (each.matches(asset.type())) {
                    count++;
                }
            }
            if (each != Filter.ALL && count == 0 && each != filter) {
                continue;
            }
            String label = each.label + (assets.isEmpty() ? "" : "  " + count);
            filterRow.addView(style.chip(label, each == filter, v -> {
                filter = each;
                renderFilters();
                renderAssets();
            }));
        }
        assetsHeading.setText(assets.isEmpty() ? "Your assets" : "Your assets  (" + assets.size()
                + (nextPage == null ? "" : "+") + ")");
    }

    /** UI thread. */
    private void renderAssets() {
        assetList.removeAllViews();
        int shown = 0;
        for (IonAsset asset : assets) {
            if (!filter.matches(asset.type())) {
                continue;
            }
            if (shown > 0) {
                assetList.addView(style.divider());
            }
            assetList.addView(assetRow(asset));
            shown++;
        }
        if (!isConfigured()) {
            assetSummary.setText(IonSignIn.isAvailable()
                    ? "Sign in at the top of this pane, and your Cesium ion assets appear here."
                    : "Add an access token in Settings, and your Cesium ion assets appear here.");
        } else if (assets.isEmpty()) {
            assetSummary.setText(connection == Connection.CHECKING ? "Loading your assets…"
                    : currentSearch != null ? "No assets match \"" + currentSearch + "\"."
                    : "This account has no assets yet. Upload data at ion.cesium.com, or add a layer "
                            + "from Cesium below.");
        } else {
            assetSummary.setText("Tap Add to map to show an asset in ATAK."
                    + (filter == Filter.ALL ? "" : " Showing " + filter.label.toLowerCase(Locale.ROOT) + " only."));
        }
        loadMore.setVisibility(nextPage == null ? View.GONE : View.VISIBLE);
        loadMore.setEnabled(true);
    }

    private View assetRow(IonAsset asset) {
        String action;
        PaneStyle.ButtonKind kind = PaneStyle.ButtonKind.PRIMARY;
        boolean enabled = true;
        boolean loaded = loader.isLoaded(asset.id());
        if (pending.contains(asset.id())) {
            action = loaded ? "Removing…" : "Adding…";
            kind = PaneStyle.ButtonKind.SECONDARY;
            enabled = false;
        } else if (loaded) {
            action = "Remove";
            kind = PaneStyle.ButtonKind.DANGER;
        } else if (asset.status() != AssetStatus.COMPLETE && asset.status() != AssetStatus.UNKNOWN) {
            action = asset.status() == AssetStatus.IN_PROGRESS ? asset.percentComplete() + "%" : "Unavailable";
            kind = PaneStyle.ButtonKind.SECONDARY;
            enabled = false;
        } else if (isSupported(asset.type())) {
            action = "Add to map";
            kind = PaneStyle.ButtonKind.ADD;
        } else {
            action = "Not supported";
            kind = PaneStyle.ButtonKind.SECONDARY;
            enabled = false;
        }
        StringBuilder detail = new StringBuilder(describeType(asset));
        if (asset.status() != AssetStatus.COMPLETE) {
            detail.append(", ").append(describeStatus(asset.status()).toLowerCase(Locale.ROOT));
        } else if (asset.bytes() > 0) {
            detail.append(", ").append(describeSize(asset.bytes()));
        }
        detail.append(", ID ").append(asset.id());
        Button button = style.button(action, kind, v -> {
            if (loader.isLoaded(asset.id())) {
                remove(asset.id(), asset.name());
            } else {
                load(asset.id(), asset.name());
            }
        });
        button.setEnabled(enabled);
        LinearLayout row = style.listRow(style.typeIcon(asset.type()), asset.name(),
                loaded ? style.onMapTag() : null, detail.toString(), null, button);
        row.setAlpha(enabled || pending.contains(asset.id()) ? 1f : 0.6f);
        return row;
    }

    private static boolean isSupported(AssetType type) {
        return type == AssetType.IMAGERY || type == AssetType.TILES_3D || type == AssetType.GEOJSON
                || type == AssetType.KML || type == AssetType.TERRAIN;
    }

    private static String describeType(IonAsset asset) {
        switch (asset.type()) {
            case TILES_3D:
                return "3D Tiles";
            case IMAGERY:
                return "Imagery";
            case TERRAIN:
                return "Terrain";
            case GEOJSON:
                return "GeoJSON";
            case UNKNOWN:
                return asset.rawType() == null ? "Unknown type" : asset.rawType();
            default:
                return asset.type().ionName();
        }
    }

    private static String describeStatus(AssetStatus status) {
        String name = status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    static String describeSize(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.US, "%.1f GB", bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return String.format(Locale.US, "%.1f MB", bytes / (double) (1L << 20));
        }
        return Math.max(1, bytes / 1024) + " KB";
    }

    /** Where ATAK lists a loaded layer, which is also where to hide it. */
    static String whereInAtak(LoadedAssets.Kind kind) {
        switch (kind) {
            case TILES_3D:
                return "Overlay Manager › 3D Models";
            case IMAGERY:
                return "Map Layers › Mobile";
            case TERRAIN:
                return "ATAK elevation. Remove it here to turn it off";
            default:
                return "Overlay Manager › File Overlays";
        }
    }

    private static AssetType assetType(LoadedAssets.Kind kind) {
        switch (kind) {
            case TILES_3D:
                return AssetType.TILES_3D;
            case IMAGERY:
                return AssetType.IMAGERY;
            case TERRAIN:
                return AssetType.TERRAIN;
            default:
                return AssetType.GEOJSON;
        }
    }

    /** "In ATAK:" muted, then the place in ATAK's menus. */
    private static CharSequence inAtak(LoadedAssets.Kind kind) {
        SpannableStringBuilder text = new SpannableStringBuilder("In ATAK: ");
        text.setSpan(new ForegroundColorSpan(PaneStyle.MUTED), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(whereInAtak(kind));
        return text;
    }

    /** Lists loaded layers (from any page or search) and the featured row. UI thread. */
    private void renderLoaded() {
        loadedList.removeAllViews();
        List<LoadedAssets.Entry> entries = loader.loadedEntries();
        removeAllButton.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        loadedHint.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        loadedHeading.setText(entries.isEmpty() ? "On your map" : "On your map  (" + entries.size() + ")");
        if (entries.isEmpty()) {
            TextView none = style.text(isConfigured()
                    ? "Nothing from Cesium ion is on the map yet. Pick one of your assets below and tap "
                            + "Add to map."
                    : "Nothing from Cesium ion is on the map yet.", 13, PaneStyle.MUTED);
            none.setPadding(0, 0, 0, style.dp(4));
            loadedList.addView(none);
        }
        for (int i = 0; i < entries.size(); i++) {
            LoadedAssets.Entry entry = entries.get(i);
            if (i > 0) {
                loadedList.addView(style.divider());
            }
            boolean busy = pending.contains(entry.assetId);
            Button button = style.button(busy ? "Removing…" : "Remove",
                    busy ? PaneStyle.ButtonKind.SECONDARY : PaneStyle.ButtonKind.DANGER,
                    v -> remove(entry.assetId, entry.name));
            button.setEnabled(!busy);
            LinearLayout row = style.listRow(style.typeIcon(assetType(entry.kind)), entry.name, null,
                    null, null, button);
            // The ATAK location is the line people need: full text, not muted.
            LinearLayout labels = (LinearLayout) row.getChildAt(1);
            TextView where = style.text("", 13, PaneStyle.TEXT);
            where.setText(inAtak(entry.kind));
            where.setPadding(0, style.dp(3), 0, 0);
            labels.addView(where);
            loadedList.addView(row);
        }

        featuredList.removeAllViews();
        featuredShown = 0;
        for (Featured featured : FEATURED) {
            if (inAssetList(featured.assetId)) {
                continue; // already listed under Your assets
            }
            if (featuredShown++ > 0) {
                featuredList.addView(style.divider());
            }
            boolean loaded = loader.isLoaded(featured.assetId);
            boolean busy = pending.contains(featured.assetId);
            Button button = style.button(busy ? (loaded ? "Removing…" : "Adding…")
                            : loaded ? "Remove" : "Add to map",
                    busy ? PaneStyle.ButtonKind.SECONDARY
                            : loaded ? PaneStyle.ButtonKind.DANGER : PaneStyle.ButtonKind.ADD,
                    v -> {
                        if (loader.isLoaded(featured.assetId)) {
                            remove(featured.assetId, featured.name);
                        } else {
                            load(featured.assetId, featured.name);
                        }
                    });
            button.setEnabled(!busy && isConfigured());
            featuredList.addView(style.listRow(style.typeIcon(featured.type), featured.name,
                    loaded ? style.onMapTag() : null, featured.detail, null, button));
        }
        updateFeaturedVisibility();
    }

    private boolean inAssetList(long assetId) {
        for (IonAsset asset : assets) {
            if (asset.id() == assetId) {
                return true;
            }
        }
        return false;
    }

    /** Featured layers are on Cesium ion Cloud only, and hidden when all are already listed. */
    private void updateFeaturedVisibility() {
        if (featuredSection != null) {
            boolean cloud = currentSettings().mode == IonSettings.Mode.CLOUD;
            featuredSection.setVisibility(cloud && featuredShown > 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void refreshListsOnUi() {
        if (root != null) {
            root.post(() -> {
                renderLoaded();
                renderAssets();
            });
        }
    }

    /** UI thread: marks {@code assetId} busy until {@link #settled} runs. */
    private void markPending(long assetId) {
        if (root == null) {
            return;
        }
        pending.add(assetId);
        renderLoaded();
        renderAssets();
    }

    private void settled(long assetId) {
        if (root != null) {
            root.post(() -> {
                pending.remove(assetId);
                renderLoaded();
                renderAssets();
            });
        }
    }

    void load(long assetId, String name) {
        if (root != null) {
            root.post(() -> markPending(assetId));
        }
        run("Add " + name + " (#" + assetId + ")", () -> {
            AssetLoader.Result result;
            try {
                result = loader.load(assetId, name, this::append);
            } finally {
                settled(assetId);
            }
            refreshListsOnUi();
            if (result.entry.kind == LoadedAssets.Kind.IMAGERY) {
                mapView.post(() -> selectImagery(result.entry.name));
                return;
            }
            if (result.entry.kind == LoadedAssets.Kind.TERRAIN) {
                append("Added as ATAK's elevation. Tilt the map over hills or mountains to see it.");
                return;
            }
            if (result.entry.kind == LoadedAssets.Kind.VECTOR) {
                append("Added. In ATAK: " + whereInAtak(LoadedAssets.Kind.VECTOR) + ".");
                if (result.bounds != null) {
                    mapView.post(() -> flyTo(result.bounds));
                }
                return;
            }
            append("Added. In ATAK: " + whereInAtak(LoadedAssets.Kind.TILES_3D) + ".");
            mapView.post(() -> showTileset(result));
        });
    }

    /** UI thread: frame a newly loaded tileset. */
    private void showTileset(AssetLoader.Result result) {
        enableGestures();
        if (result.photorealistic) {
            // Photorealistic tiles carry their own ground: hide ATAK's basemap
            // surface (as CesiumJS hides its globe).
            setBasemapOpacity(0);
            append("Zoom to street level over a city to see the photorealistic tiles.");
        } else if (result.bounds != null) {
            // One camera move to the tileset, tilted to show it in 3D.
            moveMap(result.bounds.latitude(), result.bounds.longitude(), result.bounds.height(),
                    Math.max(3 * result.bounds.radius(), 300.0), 45.0, true);
            return;
        } else {
            append("The tileset has no georeferenced root volume; pan to it manually.");
        }
        mapView.getMapController().tiltTo(45.0, true);
    }

    private void flyTo(TilesetBounds bounds) {
        // Overlays (GeoJSON) carry no reliable height: look the ground up.
        moveMap(bounds.latitude(), bounds.longitude(), Double.NaN, Math.max(3 * bounds.radius(), 300.0),
                Double.NaN, true);
    }

    /**
     * Test command (experimental): asks ATAK's Map Layers to show the mobile
     * layer named {@code layerName}, via its SELECT_LAYER broadcast.
     */
    private static final int SELECT_ATTEMPTS = 5;
    private static final int LOAD_FIRST_SCAN = 100;

    void selectImagery(String selection) {
        // ATAK 5.7's LayersManagerBroadcastReceiver matches "layerName"
        // against a raster layer's name ("Mobile" holds map sources) and shows
        // that layer. It then calls setLocked, which resets the layer's
        // selection to auto-select, so the selection itself is set on the
        // layer directly once ATAK lists the new map source.
        List<String> rasterLayers = MapLayerNames.list(mapView);
        PluginLog.i("Map layers: " + rasterLayers);
        for (String layerName : MapLayerNames.selectionCandidates(rasterLayers)) {
            Intent intent = new Intent("com.atakmap.android.maps.SELECT_LAYER");
            intent.putExtra("layerName", layerName);
            intent.putExtra("selection", selection);
            intent.putExtra("isLocked", true);
            AtakBroadcast.getInstance().sendBroadcast(intent);
        }
        selectWhenListed(selection, SELECT_ATTEMPTS);
    }

    /** UI thread: sets {@code selection} on the Mobile layer, retrying while ATAK imports it. */
    private void selectWhenListed(String selection, int attemptsLeft) {
        mapView.postDelayed(() -> {
            Object mobile = MapLayerNames.find(mapView, MapLayerNames.MOBILE);
            List<String> options = mobile == null ? Collections.emptyList() : MapLayerNames.selectionOptions(mobile);
            if (options.contains(selection)) {
                if (MapLayerNames.select(mobile, selection)) {
                    append("Showing \"" + selection + "\" (Map Layers › Mobile).");
                }
            } else if (attemptsLeft > 1) {
                selectWhenListed(selection, attemptsLeft - 1);
            } else {
                PluginLog.i("Mobile map sources (" + options.size() + "): " + options);
                append("Open Map Layers (toolbar) › Mobile and select \"" + selection + "\".");
            }
        }, 1_000);
    }

    /**
     * UI thread: when ATAK's Mobile layer shows one of the removed map
     * sources, hands it back to auto-select (otherwise the map stays blank).
     */
    private void deselectImagery(List<String> removed) {
        Object mobile = MapLayerNames.find(mapView, MapLayerNames.MOBILE);
        String current = mobile == null ? null : MapLayerNames.selection(mobile);
        if (current != null && removed.contains(current) && MapLayerNames.select(mobile, null)) {
            append("Map Layers › Mobile is back on automatic selection.");
        }
    }

    private String imageryName(long assetId) {
        for (LoadedAssets.Entry entry : loader.loadedEntries()) {
            if (entry.assetId == assetId && entry.kind == LoadedAssets.Kind.IMAGERY) {
                return entry.name;
            }
        }
        return null;
    }


    /**
     * Test command: centre on a point with {@code rangeMetres} across the map,
     * then tilt; {@code animate} false jumps there. UI thread.
     */
    void flyTo(double latitude, double longitude, double rangeMetres, double tilt, boolean animate) {
        enableGestures();
        moveMap(latitude, longitude, Double.NaN, rangeMetres, tilt, animate);
    }

    /**
     * {@code tilt} in degrees, or NaN to keep the current tilt; {@code height}
     * the ground height in metres HAE, or NaN to look it up (off the main
     * thread) first.
     */
    private void moveMap(double latitude, double longitude, double height, double widthMetres, double tilt,
            boolean animate) {
        if (Double.isNaN(height)) {
            camera.groundHeight(latitude, longitude, 3_000,
                    ground -> moveMapAt(latitude, longitude, ground, widthMetres, tilt, animate));
        } else {
            moveMapAt(latitude, longitude, height, widthMetres, tilt, animate);
        }
    }

    private void moveMapAt(double latitude, double longitude, double height, double widthMetres, double tilt,
            boolean animate) {
        try {
            int width = Math.max(500, mapView.getWidth());
            double metresPerPixel = widthMetres / width;
            PluginLog.i(String.format(Locale.US, "Camera to %.4f, %.4f, ground %s m, %.0f m across, tilt %s",
                    latitude, longitude, Double.isNaN(height) ? "unknown" : String.format(Locale.US, "%.0f", height),
                    widthMetres, Double.isNaN(tilt) ? "unchanged" : String.format(Locale.US, "%.0f", tilt)));
            // Without a ground height, lookAt frames the point at sea level, and a
            // tilt there can end inside high ground: tilt with ATAK's controller,
            // which pivots on the terrain, instead.
            boolean tiltSeparately = Double.isNaN(height) && !Double.isNaN(tilt);
            if (camera.lookAt(latitude, longitude, height, metresPerPixel, tiltSeparately ? Double.NaN : tilt,
                    animate)) {
                if (tiltSeparately) {
                    mapView.getMapController().tiltTo(tilt, animate);
                }
                return;
            }
            // ATAK's map scale: metres per pixel = 0.0254 / (scale * dpi).
            double dpi = hostContext.getResources().getDisplayMetrics().densityDpi;
            double scale = 0.0254 / (metresPerPixel * dpi);
            mapView.getMapController().panZoomTo(new GeoPoint(latitude, longitude), scale, animate);
            if (!Double.isNaN(tilt)) {
                mapView.getMapController().tiltTo(tilt, animate);
            }
        } catch (RuntimeException | LinkageError failure) {
            PluginLog.e("Moving the map failed", failure);
            append("Could not move the map: " + failure);
        }
    }

    /**
     * Test command: lists the server's assets into the log and loads the
     * first complete one of {@code typeName} (an {@link AssetType} name, e.g.
     * TILES_3D), so scenarios can run against servers whose asset IDs differ.
     */
    void loadFirst(String typeName) {
        run("Load the first " + typeName + " asset", () -> {
            AssetPage page = session.client().listAssets(AssetQuery.all().withLimit(LOAD_FIRST_SCAN));
            IonAsset match = null;
            for (IonAsset asset : page.items()) {
                PluginLog.i("Asset #" + asset.id() + " " + asset.type() + " " + asset.status() + " " + asset.name());
                boolean ready = asset.status() == AssetStatus.COMPLETE || asset.status() == AssetStatus.UNKNOWN;
                if (match == null && ready && asset.type().name().equals(typeName)) {
                    match = asset;
                }
            }
            if (match == null) {
                append("No " + typeName + " asset among the first " + page.items().size() + ".");
                return;
            }
            load(match.id(), match.name());
        });
    }

    /** Test command. */
    void remove(long assetId) {
        remove(assetId, "asset " + assetId);
    }

    private void remove(long assetId, String name) {
        if (root != null) {
            root.post(() -> markPending(assetId));
        }
        run("Remove " + name + " (#" + assetId + ")", () -> {
            String imagery = imageryName(assetId);
            boolean removed;
            try {
                removed = loader.remove(assetId);
            } finally {
                settled(assetId);
            }
            append(removed ? "Removal requested." : "It was not loaded.");
            refreshListsOnUi();
            if (removed && assetId == GOOGLE_PHOTOREALISTIC_ASSET_ID) {
                mapView.post(() -> setBasemapOpacity(100));
            }
            if (imagery != null) {
                mapView.post(() -> deselectImagery(Collections.singletonList(imagery)));
            }
        });
    }

    /** Also clears what earlier builds and the WebView plugin left behind. */
    void removeAll() {
        run("Remove all Cesium ion layers", () -> {
            List<String> imagery = new ArrayList<>();
            for (LoadedAssets.Entry entry : loader.loadedEntries()) {
                if (entry.kind == LoadedAssets.Kind.IMAGERY) {
                    imagery.add(entry.name);
                }
            }
            int removed = loader.removeAll(this::append);
            append(removed == 0 ? "Nothing to remove." : "Removed " + removed + " layers.");
            refreshListsOnUi();
            mapView.post(() -> {
                setBasemapOpacity(100);
                deselectImagery(imagery);
            });
        });
    }

    // ---- Map controls --------------------------------------------------

    void setBasemapOpacity(int percent) {
        basemapOpacity.setProgress(percent);
        applyBasemapOpacity(percent, true);
    }

    /** Runs on the UI thread; {@code report} logs the outcome (not on every slider step). */
    private void applyBasemapOpacity(int percent, boolean report) {
        try {
            boolean applied = basemapBlend.apply(mapView, percent / 100.0);
            if (report) {
                // Details for the log; a plain outcome for the pane.
                PluginLog.i("ATAK basemap opacity " + percent + "%: " + (applied
                        ? basemapBlend.state(mapView) + ", " + basemapBlend.description()
                        : "no terrain blend control, " + basemapBlend.description()));
                append(applied ? "ATAK basemap at " + percent + "%."
                        : "This ATAK build cannot fade its basemap.");
            }
        } catch (RuntimeException | LinkageError failure) {
            PluginLog.e("Basemap opacity change failed", failure);
            if (report) {
                append("Basemap opacity change failed: " + failure);
            }
        }
    }

    private void enableGestures() {
        try {
            append("Rotate & tilt gestures: " + MapGestures.enableRotateAndTilt(mapView)
                    + ". Twist two fingers to rotate; drag two fingers up or down to tilt.");
        } catch (RuntimeException | LinkageError failure) {
            PluginLog.e("Enabling gestures failed", failure);
            append("Enabling rotate & tilt failed: " + failure);
        }
    }

    /** Signs in (browser), cancels a sign-in in progress, or signs out. UI thread. */
    void signInOrOut() {
        IonSettings settings = currentSettings();
        if (signIn.isRunning()) {
            signIn.cancel();
            return;
        }
        if (tokens.has(settings.tokenSlot())) {
            tokens.clear(settings.tokenSlot());
            append("Signed out of " + settings.describe() + "; the saved access was removed from this device.");
            refreshStatus();
            return;
        }
        startSignIn();
    }

    /**
     * Opens ion's sign-in page; never signs out. The saved access changes only
     * if the person completes sign-in. UI thread.
     */
    void startSignIn() {
        if (signIn.isRunning()) {
            return;
        }
        if (!IonSignIn.isAvailable()) {
            showSettings(true);
            return;
        }
        IonSettings settings = currentSettings();
        append("> Sign in to " + settings.describe());
        signIn.start(settings, this::append, this::refreshStatusOnUi);
        refreshStatus();
    }

    private void toggleSettings() {
        showSettings(settingsPanel.getVisibility() != View.VISIBLE);
    }

    void showSettings(boolean show) {
        if (show) {
            settingsForm.view(); // reload current values
            container.smoothScrollTo(0, 0);
        }
        settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        settingsGear.setSelected(show);
        refreshStatus();
    }

    /** Settings were saved or the token removed. UI thread. */
    private void settingsSaved() {
        refreshStatus();
        if (isConfigured()) {
            showSettings(false);
        }
    }

    private void testConnection() {
        connection = Connection.CHECKING;
        refreshStatus();
        run("Test connection", () -> {
            ConnectionCheck check;
            try {
                IonClient client = session.client();
                check = client.verify();
                connected();
            } catch (IonException failure) {
                disconnected(failure);
                throw failure;
            }
            append("Connected. API " + check.apiUrl());
            if (check.profile() != null) {
                append("Signed in as " + check.profile().username());
            }
            append("The token can list assets.");
        });
    }

    private IonSettings currentSettings() {
        return IonSettings.from(atakPreferences.getAll());
    }

    private interface Task {
        void run() throws Exception;
    }

    private void run(String label, Task task) {
        append("> " + label);
        try {
            worker.execute(() -> {
                try {
                    task.run();
                } catch (IonException failure) {
                    PluginLog.w(label + " failed (" + failure.kind() + ")");
                    append(failure.getMessage());
                } catch (Exception failure) {
                    PluginLog.e(label + " failed", failure);
                    append(label + " failed: " + failure.getMessage());
                } finally {
                    refreshStatusOnUi();
                }
            });
        } catch (RejectedExecutionException stopped) {
            append("The plugin is stopping.");
        }
    }

    private void refreshStatusOnUi() {
        if (root != null) {
            root.post(this::refreshStatus);
        }
    }

    /** Adds a redacted, timestamped line to the pane log and logcat. Thread-safe. */
    void append(String message) {
        String line = clock.format(new Date()) + "  " + Redactor.redact(message);
        PluginLog.i(message);
        synchronized (logLines) {
            logLines.addLast(line);
            while (logLines.size() > MAX_LOG_LINES) {
                logLines.removeFirst();
            }
        }
        if (log == null) {
            return;
        }
        String latest = Redactor.redact(message);
        log.post(() -> {
            lastActivity.setText(latest);
            lastActivity.setVisibility(View.VISIBLE);
            StringBuilder text = new StringBuilder();
            synchronized (logLines) {
                for (String entry : logLines) {
                    text.append(entry).append('\n');
                }
            }
            log.setText(text);
        });
    }

    private static void setText(TextView view, String value) {
        view.post(() -> view.setText(value));
    }
}
