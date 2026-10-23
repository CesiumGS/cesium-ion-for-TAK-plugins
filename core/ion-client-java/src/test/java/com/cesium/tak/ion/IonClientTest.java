package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.model.AssetPage;
import com.cesium.tak.ion.model.Defaults;
import com.cesium.tak.ion.model.Team;
import com.cesium.tak.ion.util.Secret;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class IonClientTest {
    private static final String API = "https://api.cesium.com/";
    private final FakeTransport transport = new FakeTransport();
    private final IonClient client = new IonClient(IonServer.cloud(), Secret.of("user-token"), transport);

    @Test
    void verifyReportsProfileWhenAvailable() throws Exception {
        transport.respond(API + "v1/me", 200, "{\"id\":7,\"username\":\"operator\",\"scopes\":[\"assets:list\"]}")
                .respond(API + "v1/assets?limit=1", 200, "{\"items\":[]}");
        ConnectionCheck check = client.verify();
        assertEquals("https://api.cesium.com/", check.apiUrl().toString());
        assertEquals("operator", check.profile().username());
        assertEquals(List.of("assets:list"), check.profile().scopes());
    }

    @Test
    void verifyToleratesMissingProfileScope() throws Exception {
        transport.respond(API + "v1/me", 403, "{\"code\":\"Forbidden\",\"message\":\"missing scope profile:read\"}")
                .respond(API + "v1/assets?limit=1", 200, "{\"items\":[]}");
        assertNull(client.verify().profile());
    }

    @Test
    void verifyExplainsMissingAssetScopes() {
        transport.respond(API + "v1/me", 403, "{}").respond(API + "v1/assets?limit=1", 403, "{}");
        IonException failure = assertThrows(IonException.class, client::verify);
        assertEquals(IonException.Kind.FORBIDDEN, failure.kind());
        assertTrue(failure.getMessage().contains("assets:list"));
    }

    @Test
    void mapsHttpFailuresToKinds() {
        transport.respond(API + "v1/assets/1", 401, "{\"code\":\"InvalidCredentials\",\"message\":\"Invalid access token\"}")
                .respond(API + "v1/assets/2", 404, "")
                .respond(API + "v1/assets/3", 429, "")
                .respond(API + "v1/assets/4", 502, "<html>bad gateway</html>")
                .fail(API + "v1/assets/5")
                .respond(API + "v1/assets/6", 200, "not json");
        IonException unauthorized = assertThrows(IonException.class, () -> client.asset(1));
        assertEquals(IonException.Kind.UNAUTHORIZED, unauthorized.kind());
        assertTrue(unauthorized.getMessage().contains("Invalid access token"));
        assertEquals(IonException.Kind.NOT_FOUND, assertThrows(IonException.class, () -> client.asset(2)).kind());
        assertEquals(IonException.Kind.RATE_LIMITED, assertThrows(IonException.class, () -> client.asset(3)).kind());
        IonException server = assertThrows(IonException.class, () -> client.asset(4));
        assertEquals(IonException.Kind.SERVER, server.kind());
        assertEquals(502, server.httpStatus());
        assertEquals(IonException.Kind.NETWORK, assertThrows(IonException.class, () -> client.asset(5)).kind());
        assertEquals(IonException.Kind.INVALID_RESPONSE, assertThrows(IonException.class, () -> client.asset(6)).kind());
        assertEquals(IonException.Kind.CONFIGURATION, assertThrows(IonException.class, () -> client.asset(0)).kind());
    }

    @Test
    void errorsNeverContainTheToken() {
        transport.respond(API + "v1/assets/1", 401, "{\"message\":\"token user-token is invalid; Bearer user-token\"}");
        IonException failure = assertThrows(IonException.class, () -> client.asset(1));
        assertTrue(failure.getMessage().contains("Bearer REDACTED"), failure.getMessage());
    }

    @Test
    void pagesWithTokenThenLinkHeader() throws Exception {
        transport.respond(API + "v1/assets", 200, "{\"items\":[{\"id\":1,\"type\":\"3DTILES\"}],\"nextPageToken\":\"t2\"}")
                .respond(API + "v1/assets?pageToken=t2", 200, "{\"items\":[{\"id\":2,\"type\":\"IMAGERY\"}]}",
                        Map.of("Link", List.of("<https://api.cesium.com/v1/assets?page=3>; rel=\"next\"")))
                .respond(API + "v1/assets?page=3", 200, "{\"items\":[{\"id\":3,\"type\":\"TERRAIN\"}]}",
                        Map.of("Link", List.of("<https://evil.example.com/v1/assets?page=4>; rel=\"next\"")));

        AssetPage first = client.listAssets(AssetQuery.all());
        AssetPage second = client.listAssets(AssetQuery.all().after(first.next()));
        AssetPage third = client.listAssets(AssetQuery.all().after(second.next()));
        assertEquals(1, first.items().get(0).id());
        assertEquals(2, second.items().get(0).id());
        assertEquals(3, third.items().get(0).id());
        assertFalse(third.hasNext(), "a next link to another host must be ignored");
    }

    @Test
    void teamScopingAppliesToAssetCalls() throws Exception {
        String endpointJson = "{\"type\":\"IMAGERY\",\"url\":\"https://assets.ion.cesium.com/9/\",\"accessToken\":\"t\"}";
        transport.respond(API + "v1/assets?team=42", 200, "{\"items\":[]}")
                .respond(API + "v1/assets/9/endpoint?team=42", 200, endpointJson)
                .respond(API + "v1/me", 200, "{\"id\":1}");
        IonClient team = client.forTeam(" 42 ");
        team.listAssets(AssetQuery.all());
        AssetEndpoint endpoint = team.endpoint(9);
        team.me();
        assertEquals(AssetEndpoint.Kind.ION_HOSTED, endpoint.kind());
        assertEquals(List.of(API + "v1/assets?team=42", API + "v1/assets/9/endpoint?team=42", API + "v1/me"),
                transport.requestedUris());
    }

    @Test
    void teamsToleratesUnsupportedDeployments() throws Exception {
        transport.respond(API + "v1/teams", 404, "");
        assertEquals(Collections.emptyList(), client.teams());
    }

    @Test
    void teamsAcceptsArrayOrItemsAndDeduplicates() throws Exception {
        transport.respond(API + "v1/teams", 200,
                "{\"items\":[{\"id\":5,\"name\":\"Ops\",\"default\":true},{\"id\":\"6\",\"displayName\":\"Lab\"},"
                        + "{\"id\":5,\"name\":\"Ops again\"},{\"name\":\"no id\"}]}");
        List<Team> teams = client.teams();
        assertEquals(2, teams.size());
        assertEquals("Ops again", teams.get(0).name());
        assertEquals("Lab", teams.get(1).name());
    }

    @Test
    void parsesDefaults() throws Exception {
        transport.respond(API + "v1/defaults", 200,
                "{\"defaultAssets\":{\"imagery\":2,\"terrain\":1,\"buildings\":96188},"
                        + "\"quickAddAssets\":[{\"assetId\":2275207,\"name\":\"Google Photorealistic 3D Tiles\","
                        + "\"type\":\"3DTILES\",\"subscribed\":true},{\"name\":\"no id\"}]}");
        Defaults defaults = client.defaults();
        assertEquals(1, defaults.terrainAssetId());
        assertEquals(96188, defaults.buildingsAssetId());
        assertEquals(1, defaults.quickAddAssets().size());
        assertEquals(2275207, defaults.quickAddAssets().get(0).assetId());
        assertTrue(defaults.quickAddAssets().get(0).isSubscribed());
    }

    @Test
    void selfHostedDiscoveryHappensOnceAndIsShared() throws Exception {
        FakeTransport selfHostedTransport = new FakeTransport()
                .respond("https://ion.example.mil/config.json", 200, "{\"apiHostname\":\"api.example.mil\"}")
                .respond("https://api.example.mil/v1/assets", 200, "{\"items\":[]}")
                .respond("https://api.example.mil/v1/assets?team=1", 200, "{\"items\":[]}");
        IonClient selfHosted = new IonClient(
                IonServer.selfHosted("https://ion.example.mil", null, false), Secret.of("t"), selfHostedTransport);
        selfHosted.listAssets(AssetQuery.all());
        selfHosted.forTeam("1").listAssets(AssetQuery.all());
        assertEquals(List.of(
                "https://ion.example.mil/config.json",
                "https://api.example.mil/v1/assets",
                "https://api.example.mil/v1/assets?team=1"), selfHostedTransport.requestedUris());
    }

    @Test
    void singleUserSelfHostedSendsNoAuthorization() throws Exception {
        FakeTransport lan = new FakeTransport()
                .respond("https://ion.lab.example/api/v1/assets", 200, "{\"items\":[{\"id\":3,\"type\":\"3DTILES\"}]}");
        IonClient singleUser = new IonClient(
                IonServer.selfHosted("https://ion.lab.example", "https://ion.lab.example/api", false), null, lan);
        assertEquals(3, singleUser.listAssets(AssetQuery.all()).items().get(0).id());
        assertNull(lan.requests.get(0).headers().get("Authorization"));
    }

    @Test
    void cloudRequiresAToken() {
        assertThrows(IllegalArgumentException.class, () -> new IonClient(IonServer.cloud(), null, transport));
    }

    @Test
    void encodesSearchText() throws Exception {
        transport.respond(API + "v1/assets?search=a%20b%26c&limit=5", 200, "{\"items\":[]}");
        client.listAssets(AssetQuery.all().withSearch("a b&c").withLimit(5));
        assertEquals(API + "v1/assets?search=a%20b%26c&limit=5", transport.requestedUris().get(0));
    }
}
