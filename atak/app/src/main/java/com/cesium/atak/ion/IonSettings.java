package com.cesium.atak.ion;

import com.cesium.tak.ion.IonClient;
import com.cesium.tak.ion.IonException;
import com.cesium.tak.ion.IonServer;
import com.cesium.tak.ion.http.HttpTransport;
import com.cesium.tak.ion.util.Secret;

import java.util.Map;
import java.util.Objects;

/**
 * The plugin's non-secret connection settings, as stored by Tool Preferences
 * in ATAK's preferences. Free of Android types so it is unit-testable.
 */
final class IonSettings {
    static final String KEY_MODE = "cesium_ion_mode";
    static final String KEY_SERVER_URL = "cesium_ion_server_url";
    static final String KEY_API_URL = "cesium_ion_api_url";
    static final String KEY_ALLOW_HTTP = "cesium_ion_allow_http";
    static final String KEY_SINGLE_USER = "cesium_ion_single_user";
    /** The team workspace (team id) per server kind: {@code cesium_ion_team_cloud}, {@code ..._self_hosted}. */
    static final String KEY_TEAM_PREFIX = "cesium_ion_team_";
    /** The selected team's name, for display only. */
    static final String KEY_TEAM_NAME_PREFIX = "cesium_ion_team_name_";

    enum Mode {
        CLOUD("cloud"),
        SELF_HOSTED("self_hosted");

        final String id;

        Mode(String id) {
            this.id = id;
        }

        static Mode fromId(Object id) {
            return SELF_HOSTED.id.equals(id) ? SELF_HOSTED : CLOUD;
        }
    }

    final Mode mode;
    final String serverUrl;
    final String apiUrl;
    final boolean allowHttp;
    final boolean singleUser;
    /** The team workspace's id, or empty for the personal workspace. */
    final String teamId;
    /** The team workspace's name (display only), or empty. */
    final String teamName;

    IonSettings(Mode mode, String serverUrl, String apiUrl, boolean allowHttp, boolean singleUser) {
        this(mode, serverUrl, apiUrl, allowHttp, singleUser, "", "");
    }

    IonSettings(Mode mode, String serverUrl, String apiUrl, boolean allowHttp, boolean singleUser,
            String teamId, String teamName) {
        this.mode = mode;
        this.serverUrl = trim(serverUrl);
        this.apiUrl = trim(apiUrl);
        this.allowHttp = allowHttp;
        this.singleUser = singleUser;
        this.teamId = trim(teamId);
        this.teamName = this.teamId.isEmpty() ? "" : trim(teamName);
    }

    static IonSettings from(Map<String, ?> preferences) {
        Mode mode = Mode.fromId(preferences.get(KEY_MODE));
        return new IonSettings(
                mode,
                asString(preferences.get(KEY_SERVER_URL)),
                asString(preferences.get(KEY_API_URL)),
                Boolean.TRUE.equals(preferences.get(KEY_ALLOW_HTTP)),
                Boolean.TRUE.equals(preferences.get(KEY_SINGLE_USER)),
                asString(preferences.get(KEY_TEAM_PREFIX + mode.id)),
                asString(preferences.get(KEY_TEAM_NAME_PREFIX + mode.id)));
    }

    /** Whether a preference key belongs to these settings. */
    static boolean isSettingsKey(String key) {
        return KEY_MODE.equals(key) || KEY_SERVER_URL.equals(key) || KEY_API_URL.equals(key)
                || KEY_ALLOW_HTTP.equals(key) || KEY_SINGLE_USER.equals(key)
                || (key != null && key.startsWith(KEY_TEAM_PREFIX) && !key.startsWith(KEY_TEAM_NAME_PREFIX));
    }

    /** The preference key holding the team workspace for this server kind. */
    String teamKey() {
        return KEY_TEAM_PREFIX + mode.id;
    }

    String teamNameKey() {
        return KEY_TEAM_NAME_PREFIX + mode.id;
    }

    /** Whether this configuration authenticates with an access token. */
    boolean needsToken() {
        return !(mode == Mode.SELF_HOSTED && singleUser);
    }

    /**
     * Which saved token applies. Cloud and Self-Hosted tokens are kept apart
     * so switching modes never sends one server's token to the other.
     */
    String tokenSlot() {
        return mode.id;
    }

    IonServer server() throws IonException {
        if (mode == Mode.CLOUD) {
            return IonServer.cloud();
        }
        if (serverUrl.isEmpty()) {
            throw new IonException(IonException.Kind.CONFIGURATION,
                    "Set the Self-Hosted server URL in the Cesium ion pane's Settings.");
        }
        return IonServer.selfHosted(serverUrl, apiUrl, allowHttp);
    }

    /**
     * A client for these settings.
     *
     * @param token the saved token for {@link #tokenSlot()}, or {@code null}
     */
    IonClient openClient(Secret token, HttpTransport transport) throws IonException {
        IonServer server = server();
        if (!needsToken()) {
            return new IonClient(server, null, transport).forTeam(teamId);
        }
        if (token == null) {
            throw new IonException(IonException.Kind.CONFIGURATION,
                    "Add a Cesium ion access token in the Cesium ion pane's Settings.");
        }
        return new IonClient(server, token, transport).forTeam(teamId);
    }

    /** One line for the pane, e.g. "Cesium ion Cloud" or "Self-Hosted https://ion.example.mil/". */
    String describe() {
        if (mode == Mode.CLOUD) {
            return "Cesium ion Cloud";
        }
        if (serverUrl.isEmpty()) {
            return "Self-Hosted (server URL not set)";
        }
        return "Self-Hosted " + serverUrl + (singleUser ? " (single-user)" : "");
    }

    /** "Personal" or the team's name: whose assets the pane lists. */
    String describeWorkspace() {
        return teamId.isEmpty() ? "Personal" : teamName.isEmpty() ? "Team " + teamId : teamName;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof IonSettings)) {
            return false;
        }
        IonSettings that = (IonSettings) other;
        return mode == that.mode && serverUrl.equals(that.serverUrl) && apiUrl.equals(that.apiUrl)
                && allowHttp == that.allowHttp && singleUser == that.singleUser && teamId.equals(that.teamId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, serverUrl, apiUrl, allowHttp, singleUser, teamId);
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
