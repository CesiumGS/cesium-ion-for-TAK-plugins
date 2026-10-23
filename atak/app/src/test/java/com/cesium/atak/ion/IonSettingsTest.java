package com.cesium.atak.ion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.cesium.tak.ion.IonClient;
import com.cesium.tak.ion.IonException;
import com.cesium.tak.ion.IonServer;
import com.cesium.tak.ion.http.HttpTransport;
import com.cesium.tak.ion.util.Secret;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class IonSettingsTest {
    private static final HttpTransport NO_NETWORK = request -> {
        throw new AssertionError("no network in unit tests");
    };

    @Test
    public void defaultsToCloud() throws Exception {
        IonSettings settings = IonSettings.from(new HashMap<>());
        assertEquals(IonSettings.Mode.CLOUD, settings.mode);
        assertEquals(IonServer.Kind.CLOUD, settings.server().kind());
        assertTrue(settings.needsToken());
        assertEquals("cloud", settings.tokenSlot());
        assertEquals("Cesium ion Cloud", settings.describe());
    }

    @Test
    public void selfHostedSettings() throws Exception {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put(IonSettings.KEY_MODE, "self_hosted");
        preferences.put(IonSettings.KEY_SERVER_URL, "  https://ion.example.mil  ");
        preferences.put(IonSettings.KEY_API_URL, "https://ion.example.mil/api");
        IonSettings settings = IonSettings.from(preferences);
        IonServer server = settings.server();
        assertEquals(IonServer.Kind.SELF_HOSTED, server.kind());
        assertEquals("https://ion.example.mil/", server.serverUrl().toString());
        assertEquals("https://ion.example.mil/api/", server.apiUrlOverride().toString());
        assertEquals("self_hosted", settings.tokenSlot());
    }

    @Test
    public void singleUserNeedsNoToken() throws Exception {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put(IonSettings.KEY_MODE, "self_hosted");
        preferences.put(IonSettings.KEY_SERVER_URL, "https://ion.lab.example");
        preferences.put(IonSettings.KEY_SINGLE_USER, true);
        IonSettings settings = IonSettings.from(preferences);
        assertFalse(settings.needsToken());
        IonClient client = settings.openClient(null, NO_NETWORK);
        assertEquals(IonServer.Kind.SELF_HOSTED, client.server().kind());
    }

    @Test
    public void singleUserIsIgnoredForCloud() {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put(IonSettings.KEY_SINGLE_USER, true);
        assertTrue(IonSettings.from(preferences).needsToken());
    }

    @Test
    public void missingTokenOrServerIsAConfigurationError() {
        expectConfiguration(() -> IonSettings.from(new HashMap<>()).openClient(null, NO_NETWORK));
        Map<String, Object> preferences = new HashMap<>();
        preferences.put(IonSettings.KEY_MODE, "self_hosted");
        expectConfiguration(() -> IonSettings.from(preferences).openClient(Secret.of("t"), NO_NETWORK));
        preferences.put(IonSettings.KEY_SERVER_URL, "http://ion.lab.example");
        expectConfiguration(() -> IonSettings.from(preferences).openClient(Secret.of("t"), NO_NETWORK));
        preferences.put(IonSettings.KEY_ALLOW_HTTP, true);
        try {
            IonSettings.from(preferences).openClient(Secret.of("t"), NO_NETWORK);
        } catch (IonException unexpected) {
            fail("explicitly allowed HTTP should be accepted: " + unexpected.getMessage());
        }
    }

    @Test
    public void unknownValuesFallBackSafely() {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put(IonSettings.KEY_MODE, "something-else");
        preferences.put(IonSettings.KEY_ALLOW_HTTP, "true"); // wrong type: not a boolean
        IonSettings settings = IonSettings.from(preferences);
        assertEquals(IonSettings.Mode.CLOUD, settings.mode);
        assertFalse(settings.allowHttp);
    }

    @Test
    public void equalityTracksEverySetting() {
        IonSettings base = new IonSettings(IonSettings.Mode.SELF_HOSTED, "https://a", "", false, false);
        assertEquals(base, new IonSettings(IonSettings.Mode.SELF_HOSTED, " https://a ", null, false, false));
        assertNotEquals(base, new IonSettings(IonSettings.Mode.SELF_HOSTED, "https://a", "", true, false));
        assertNotEquals(base, new IonSettings(IonSettings.Mode.SELF_HOSTED, "https://a", "", false, true));
        assertTrue(IonSettings.isSettingsKey(IonSettings.KEY_SERVER_URL));
        assertFalse(IonSettings.isSettingsKey(IonPreferenceFragmentKeys.TOKEN_ENTRY));
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void expectConfiguration(Action action) {
        try {
            action.run();
            fail("expected a configuration error");
        } catch (IonException expected) {
            assertEquals(IonException.Kind.CONFIGURATION, expected.kind());
        } catch (Exception other) {
            fail("unexpected " + other);
        }
    }

    /** Mirrors IonPreferenceFragment's keys without loading the ATAK fragment class. */
    private static final class IonPreferenceFragmentKeys {
        static final String TOKEN_ENTRY = "cesium_ion_token_entry";
    }

    @Test
    public void teamWorkspaceIsPerServerKind() throws Exception {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put(IonSettings.KEY_TEAM_PREFIX + "cloud", "42");
        preferences.put(IonSettings.KEY_TEAM_NAME_PREFIX + "cloud", "Field Team");
        IonSettings cloud = IonSettings.from(preferences);
        assertEquals("42", cloud.teamId);
        assertEquals("Field Team", cloud.describeWorkspace());
        assertEquals("cesium_ion_team_cloud", cloud.teamKey());
        IonClient client = cloud.openClient(Secret.of("token"), NO_NETWORK);
        assertEquals("42", client.teamId());

        preferences.put(IonSettings.KEY_MODE, "self_hosted");
        preferences.put(IonSettings.KEY_SERVER_URL, "https://ion.example.mil");
        IonSettings selfHosted = IonSettings.from(preferences);
        assertEquals("", selfHosted.teamId);
        assertEquals("Personal", selfHosted.describeWorkspace());
        assertEquals(null, selfHosted.openClient(Secret.of("token"), NO_NETWORK).teamId());
        assertNotEquals(cloud, selfHosted);
    }

    @Test
    public void teamKeyIsASettingsKeyButItsNameIsNot() {
        assertTrue(IonSettings.isSettingsKey("cesium_ion_team_cloud"));
        assertFalse(IonSettings.isSettingsKey("cesium_ion_team_name_cloud"));
        assertFalse(IonSettings.isSettingsKey("unrelated"));
    }
}
