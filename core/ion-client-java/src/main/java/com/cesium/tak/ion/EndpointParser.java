package com.cesium.tak.ion;

import com.cesium.tak.ion.json.JsonObject;
import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.model.AssetType;
import com.cesium.tak.ion.model.Attribution;
import com.cesium.tak.ion.tiles.TileUrls;
import com.cesium.tak.ion.util.Jwt;
import com.cesium.tak.ion.util.Secret;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns an asset endpoint response into an {@link AssetEndpoint}. The cases
 * are specified in {@code spec/ion-contract/cases/endpoints.json}.
 */
public final class EndpointParser {
    private EndpointParser() {
    }

    public static AssetEndpoint parse(long assetId, JsonObject json) throws IonException {
        AssetType type = AssetType.fromIon(json.optString("type", null));
        List<Attribution> attributions = attributions(json);
        String externalType = json.optNonBlank("externalType");

        if (externalType == null) {
            String url = json.optNonBlank("url");
            if (url == null) {
                throw invalid(assetId, "did not include a URL");
            }
            TileUrls.CredentialSplit split = split(assetId, url);
            String token = json.optNonBlank("accessToken");
            Secret accessToken = secret(assetId, token);
            long expiresAt = -1;
            long exp = Jwt.expiryEpochSeconds(token);
            if (exp > 0) {
                expiresAt = exp * 1000L;
            }
            return new AssetEndpoint.IonHosted(
                    assetId, type, split.url(), split.credentials(), accessToken, attributions, expiresAt);
        }

        JsonObject options = json.optObject("options");
        switch (externalType.toUpperCase(Locale.ROOT)) {
            case "3DTILES":
            case "GOOGLE_PHOTOREALISTIC_3DTILES": {
                String url = options.optNonBlank("url");
                if (url == null) {
                    throw invalid(assetId, "for external 3D Tiles did not include options.url");
                }
                TileUrls.CredentialSplit split = split(assetId, url);
                Map<String, Secret> credentials = new LinkedHashMap<>(split.credentials());
                Secret optionsToken = secret(assetId, options.optNonBlank("accessToken"));
                if (optionsToken != null && !credentials.containsKey("access_token")) {
                    credentials.put("access_token", optionsToken);
                }
                return new AssetEndpoint.External3DTiles(
                        assetId,
                        type == AssetType.UNKNOWN ? AssetType.TILES_3D : type,
                        split.url(),
                        credentials,
                        attributions);
            }
            case "BING": {
                String url = options.optNonBlank("url");
                String key = options.optNonBlank("key");
                String mapStyle = options.optNonBlank("mapStyle");
                if (url == null || key == null || mapStyle == null) {
                    throw invalid(assetId, "for Bing imagery was missing url, key, or mapStyle");
                }
                return new AssetEndpoint.BingImagery(
                        assetId, url, secret(assetId, key), mapStyle, options.optNonBlank("culture"), attributions);
            }
            case "GOOGLE_2D_MAPS": {
                String url = options.optNonBlank("url");
                String session = options.optNonBlank("session");
                String key = options.optNonBlank("key");
                if (url == null || session == null || key == null) {
                    throw invalid(assetId, "for Google 2D imagery was missing url, session, or key");
                }
                // ion may serve Google 2D through its own proxy, which wants
                // the endpoint token as well; it is only sent to that origin.
                String token = options.optNonBlank("accessToken");
                if (token == null) {
                    token = json.optNonBlank("accessToken");
                }
                return new AssetEndpoint.Google2DImagery(assetId, url, secret(assetId, session),
                        secret(assetId, key), secret(assetId, token), attributions);
            }
            case "STK_TERRAIN_SERVER": {
                String url = options.optNonBlank("url");
                if (url == null) {
                    throw invalid(assetId, "for external terrain did not include options.url");
                }
                TileUrls.CredentialSplit split = split(assetId, url);
                return new AssetEndpoint.ExternalTerrain(assetId, split.url(), split.credentials(), attributions);
            }
            default:
                return new AssetEndpoint.Unsupported(assetId, type, externalType, attributions);
        }
    }

    private static List<Attribution> attributions(JsonObject json) {
        List<Attribution> result = new ArrayList<>();
        for (JsonObject attribution : json.optObjects("attributions")) {
            String html = attribution.optNonBlank("html");
            if (html != null) {
                result.add(new Attribution(html, attribution.optBoolean("collapsible", false)));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static TileUrls.CredentialSplit split(long assetId, String url) throws IonException {
        String origin = TileUrls.origin(url);
        if (origin == null || !(origin.startsWith("https://") || origin.startsWith("http://"))) {
            throw invalid(assetId, "had a URL that is not absolute http(s)");
        }
        try {
            return TileUrls.splitCredentials(url);
        } catch (IllegalArgumentException invalidCredential) {
            throw invalid(assetId, "had a malformed credential in its URL");
        }
    }

    private static Secret secret(long assetId, String value) throws IonException {
        try {
            return Secret.ofNullable(value);
        } catch (IllegalArgumentException invalidCredential) {
            throw invalid(assetId, "had a malformed credential");
        }
    }

    private static IonException invalid(long assetId, String problem) {
        return new IonException(IonException.Kind.INVALID_RESPONSE,
                "The Cesium ion endpoint for asset " + assetId + " " + problem + ".");
    }
}
