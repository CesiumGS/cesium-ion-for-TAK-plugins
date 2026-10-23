package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.cesium.tak.ion.json.JsonObject;
import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.model.AssetPage;
import com.cesium.tak.ion.model.Attribution;
import com.cesium.tak.ion.model.IonAsset;
import com.cesium.tak.ion.tiles.TileUrls;
import com.cesium.tak.ion.tiles.TilesetUriRewriter;
import com.cesium.tak.ion.util.Redactor;
import com.cesium.tak.ion.util.Secret;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs every case in spec/ion-contract. The Phase 2 C++ client must pass the
 * same cases.
 */
class ContractTest {

    @TestFactory
    Stream<DynamicTest> endpoints() {
        return Contract.load("cases/endpoints.json").optObjects("cases").stream().map(testCase ->
                dynamicTest(testCase.optString("name", "?"), () -> {
                    long assetId = testCase.optLong("assetId", -1);
                    JsonObject response = testCase.optObject("response");
                    String expectedError = testCase.optString("expectError", null);
                    if (expectedError != null) {
                        IonException failure = assertThrows(IonException.class,
                                () -> EndpointParser.parse(assetId, response));
                        assertEquals(expectedError, failure.kind().name());
                        return;
                    }

                    AssetEndpoint endpoint = EndpointParser.parse(assetId, response);
                    JsonObject expect = testCase.optObject("expect");
                    assertEquals(expect.optString("kind", null), endpoint.kind().name());
                    assertEquals(expect.optString("type", null), endpoint.type().ionName());
                    assertEquals(expect.optString("url", null), endpoint.url());
                    assertEquals(expect.optStrings("credentialNames"), new ArrayList<>(endpoint.credentials().keySet()));
                    assertEquals(expect.optLong("expiresAtEpochMillis", -2), endpoint.expiresAtEpochMillis());
                    boolean google = endpoint instanceof AssetEndpoint.External3DTiles
                            && ((AssetEndpoint.External3DTiles) endpoint).isGooglePhotorealistic();
                    assertEquals(expect.optBoolean("googlePhotorealistic", false), google);

                    List<String> attributionText = new ArrayList<>();
                    for (Attribution attribution : endpoint.attributions()) {
                        attributionText.add(attribution.plainText());
                    }
                    assertEquals(expect.optStrings("attributionText"), attributionText);

                    for (JsonObject authorize : expect.optObjects("authorize")) {
                        assertEquals(authorize.optString("output", null),
                                endpoint.authorize(authorize.optString("input", null)));
                    }
                    if (expect.has("mapStyle")) {
                        assertEquals(expect.optString("mapStyle", null),
                                assertInstanceOf(AssetEndpoint.BingImagery.class, endpoint).mapStyle());
                    }
                    if (expect.has("tileTemplate")) {
                        assertEquals(expect.optString("tileTemplate", null),
                                assertInstanceOf(AssetEndpoint.Google2DImagery.class, endpoint).tileTemplate());
                    }
                    if (expect.has("externalType")) {
                        assertEquals(expect.optString("externalType", null),
                                assertInstanceOf(AssetEndpoint.Unsupported.class, endpoint).externalType());
                    }

                    // The endpoint's own description must never carry a credential.
                    for (Secret secret : endpoint.credentials().values()) {
                        assertTrue(!endpoint.toString().contains(secret.reveal()), "toString leaked a credential");
                        assertTrue(!endpoint.url().contains(secret.reveal()), "url() leaked a credential");
                    }
                }));
    }

    @TestFactory
    Stream<DynamicTest> urlResolution() {
        JsonObject cases = Contract.load("cases/url-derivation.json");
        Stream<DynamicTest> resolve = cases.optObjects("resolve").stream().map(testCase ->
                dynamicTest("resolve " + testCase.optString("reference", "?"), () -> assertEquals(
                        testCase.optString("expect", null),
                        TileUrls.resolve(testCase.optString("base", null), testCase.optString("reference", null)))));
        Stream<DynamicTest> derive = cases.optObjects("deriveChild").stream().map(testCase ->
                dynamicTest(testCase.optString("name", "?"), () -> assertEquals(
                        testCase.optString("expect", null),
                        TileUrls.deriveChild(testCase.optString("parent", null), testCase.optString("reference", null)))));
        return Stream.concat(resolve, derive);
    }

    @TestFactory
    Stream<DynamicTest> redaction() {
        return Contract.load("cases/redaction.json").optObjects("cases").stream().map(testCase ->
                dynamicTest(testCase.optString("input", "?"), () -> assertEquals(
                        testCase.optString("expect", null),
                        Redactor.redact(testCase.optString("input", null)))));
    }

    @TestFactory
    Stream<DynamicTest> selfHostedValidation() {
        return Contract.load("cases/server-config.json").optObjects("selfHostedValidation").stream().map(testCase ->
                dynamicTest("selfHosted " + testCase.optString("serverUrl", "?"), () -> {
                    String expectedError = testCase.optString("expectError", null);
                    if (expectedError != null) {
                        IonException failure = assertThrows(IonException.class, () -> selfHosted(testCase));
                        assertEquals(expectedError, failure.kind().name());
                    } else {
                        assertEquals(testCase.optString("expectServerUrl", null),
                                selfHosted(testCase).serverUrl().toString());
                    }
                }));
    }

    @TestFactory
    Stream<DynamicTest> apiDiscovery() {
        return Contract.load("cases/server-config.json").optObjects("apiDiscovery").stream().map(testCase ->
                dynamicTest(testCase.optString("name", "?"), () -> {
                    JsonObject serverJson = testCase.optObject("server");
                    IonServer server = "CLOUD".equals(serverJson.optString("kind", null))
                            ? IonServer.cloud()
                            : selfHosted(serverJson);
                    FakeTransport transport = new FakeTransport();
                    JsonObject config = testCase.optObject("config");
                    String configUrl = server.serverUrl().resolve("config.json").toString();
                    if (config.optBoolean("networkError", false)) {
                        transport.fail(configUrl);
                    } else if (config.has("status")) {
                        transport.respond(configUrl, (int) config.optLong("status", 200), config.optString("body", ""));
                    }

                    String expectedError = testCase.optString("expectError", null);
                    if (expectedError != null) {
                        IonException failure = assertThrows(IonException.class,
                                () -> ApiUrlResolver.resolve(server, transport));
                        assertEquals(expectedError, failure.kind().name());
                    } else {
                        assertEquals(testCase.optString("expectApiUrl", null),
                                ApiUrlResolver.resolve(server, transport).toString());
                    }
                    assertEquals(testCase.optStrings("expectRequests"), transport.requestedUris());
                }));
    }

    @TestFactory
    Stream<DynamicTest> assetList() {
        JsonObject contract = Contract.load("cases/assets.json");
        return Stream.of(dynamicTest("asset list page", () -> {
            FakeTransport transport = new FakeTransport()
                    .respond("https://api.cesium.com/v1/assets?search=city", 200,
                            Contract.toJson(contract.raw("response")));
            IonClient client = new IonClient(IonServer.cloud(), Secret.of("fake-user-token"), transport);
            AssetPage page = client.listAssets(AssetQuery.all().withSearch(" city "));

            List<JsonObject> expected = contract.optObject("expect").optObjects("items");
            assertEquals(expected.size(), page.items().size());
            for (int i = 0; i < expected.size(); i++) {
                JsonObject want = expected.get(i);
                IonAsset got = page.items().get(i);
                assertEquals(want.optLong("id", -1), got.id());
                assertEquals(want.optString("type", null), got.type().ionName());
                assertEquals(want.optString("status", null), got.status().name());
                assertEquals(want.optBoolean("loadable", false), got.isLoadable());
                assertEquals(want.optLong("percentComplete", -1), got.percentComplete());
            }
            assertEquals(contract.optObject("expect").optString("nextPageToken", null), page.next().pageToken());
            assertEquals("Bearer fake-user-token", transport.requests.get(0).headers().get("Authorization"));
        }));
    }

    @TestFactory
    Stream<DynamicTest> tilesetReferences() {
        return Contract.load("cases/tileset-references.json").optObjects("cases").stream().map(testCase ->
                dynamicTest(testCase.optString("fixture", "?"), () -> {
                    String json = Contract.text(testCase.optString("fixture", null));
                    String source = testCase.optString("sourceUrl", null);
                    List<String> references = TilesetUriRewriter.references(json);
                    assertEquals(testCase.optStrings("expectReferences"), references);

                    List<String> resolved = new ArrayList<>();
                    for (String reference : references) {
                        resolved.add(TileUrls.deriveChild(source, reference));
                    }
                    assertEquals(testCase.optStrings("expectResolved"), resolved);

                    TilesetUriRewriter.Result identity = TilesetUriRewriter.rewrite(json, reference -> reference);
                    assertEquals(json, identity.json(), "identity rewrite must be byte-identical");
                    assertEquals(0, identity.rewrittenCount());

                    if (testCase.has("geometricErrorCeiling")) {
                        double ceiling = testCase.optDouble("geometricErrorCeiling", 0);
                        TilesetUriRewriter.Result clamped = TilesetUriRewriter.rewrite(json, reference -> null, ceiling);
                        assertEquals(testCase.optLong("expectClamped", -1), clamped.clampedCount());
                        assertEquals(testCase.optDouble("expectMaxGeometricError", Double.NaN),
                                clamped.maxGeometricError());
                        // The clamped document still parses, and every value now respects the ceiling.
                        TilesetUriRewriter.Result rescan = TilesetUriRewriter.rewrite(clamped.json(), reference -> null);
                        assertTrue(rescan.maxGeometricError() <= ceiling);
                        assertEquals(references, rescan.references());
                        com.cesium.tak.ion.json.Json.parse(clamped.json());
                    }
                }));
    }

    private static IonServer selfHosted(JsonObject json) throws IonException {
        return IonServer.selfHosted(
                json.optString("serverUrl", null),
                json.optString("apiUrlOverride", null),
                json.optBoolean("allowCleartext", false));
    }
}
