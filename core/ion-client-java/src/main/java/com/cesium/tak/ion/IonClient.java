package com.cesium.tak.ion;

import com.cesium.tak.ion.http.HttpRequest;
import com.cesium.tak.ion.http.HttpResponse;
import com.cesium.tak.ion.http.HttpTransport;
import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonException;
import com.cesium.tak.ion.json.JsonObject;
import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.model.AssetPage;
import com.cesium.tak.ion.model.AssetStatus;
import com.cesium.tak.ion.model.Defaults;
import com.cesium.tak.ion.model.IonAsset;
import com.cesium.tak.ion.model.PageCursor;
import com.cesium.tak.ion.model.Profile;
import com.cesium.tak.ion.model.Team;
import com.cesium.tak.ion.tiles.TileUrls;
import com.cesium.tak.ion.util.Secret;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blocking client for the Cesium ion REST API (Cloud or Self-Hosted).
 *
 * <p>Calls perform network I/O; never call them on a UI thread. Instances are
 * immutable apart from a cached API URL and are safe to share between threads.
 * The REST surface used is listed in {@code spec/ion-contract/README.md}.
 */
public final class IonClient {
    private static final Pattern LINK_NEXT = Pattern.compile("<([^>]+)>\\s*;[^,]*rel\\s*=\\s*\"?next\"?");

    private final IonServer server;
    private final Secret accessToken;
    private final HttpTransport transport;
    private final String teamId;
    private final Object apiUrlLock;
    private volatile URI apiUrl;

    /**
     * @param accessToken the ion access token. May be {@code null} only for a
     *     Self-Hosted server running in single-user mode, which accepts
     *     requests without authentication; no {@code Authorization} header is
     *     sent then.
     */
    public IonClient(IonServer server, Secret accessToken, HttpTransport transport) {
        this(server, accessToken, transport, null, new Object(), null);
    }

    private IonClient(
            IonServer server,
            Secret accessToken,
            HttpTransport transport,
            String teamId,
            Object apiUrlLock,
            URI apiUrl) {
        if (server == null || transport == null) {
            throw new IllegalArgumentException("A server and transport are required.");
        }
        if (accessToken == null && server.kind() == IonServer.Kind.CLOUD) {
            throw new IllegalArgumentException("Cesium ion Cloud requires an access token.");
        }
        this.server = server;
        this.accessToken = accessToken;
        this.transport = transport;
        this.teamId = teamId;
        this.apiUrlLock = apiUrlLock;
        this.apiUrl = apiUrl;
    }

    /**
     * A client scoped to a team workspace ({@code null} for the personal
     * workspace). Asset listing, lookup, and endpoint calls pass
     * {@code team=<id>}.
     */
    public IonClient forTeam(String teamId) {
        String normalized = teamId == null || teamId.trim().isEmpty() ? null : teamId.trim();
        return new IonClient(server, accessToken, transport, normalized, apiUrlLock, apiUrl);
    }

    public IonServer server() {
        return server;
    }

    /** The selected team id, or {@code null} for the personal workspace. */
    public String teamId() {
        return teamId;
    }

    /** The REST API base, resolved once (see {@link ApiUrlResolver}). */
    public URI apiUrl() throws IonException {
        URI resolved = apiUrl;
        if (resolved != null) {
            return resolved;
        }
        synchronized (apiUrlLock) {
            if (apiUrl == null) {
                apiUrl = ApiUrlResolver.resolve(server, transport);
            }
            return apiUrl;
        }
    }

    /**
     * Checks that the server is reachable and the token can list assets.
     * Reports the token owner when the token also has {@code profile:read}.
     */
    public ConnectionCheck verify() throws IonException {
        URI api = apiUrl();
        Profile profile;
        try {
            profile = me();
        } catch (IonException failure) {
            if (failure.kind() != IonException.Kind.FORBIDDEN) {
                throw failure;
            }
            profile = null;
        }
        try {
            listAssets(AssetQuery.all().withLimit(1));
        } catch (IonException failure) {
            if (failure.kind() == IonException.Kind.FORBIDDEN) {
                throw new IonException(IonException.Kind.FORBIDDEN,
                        "The Cesium ion token cannot list assets. Create a token with the assets:list and assets:read scopes.",
                        failure.httpStatus(), failure);
            }
            throw failure;
        }
        return new ConnectionCheck(api, profile);
    }

    /** {@code GET /v1/me}. Needs the {@code profile:read} scope. */
    public Profile me() throws IonException {
        JsonObject json = getObject(api("v1/me", null), "profile");
        return new Profile(json.optLong("id", -1), json.optString("username", ""), json.optStrings("scopes"));
    }

    /**
     * {@code GET /v1/teams}. Returns an empty list when the deployment or
     * account has no readable teams (HTTP 403, 404, or 405).
     */
    public List<Team> teams() throws IonException {
        HttpResponse response = send(api("v1/teams", null));
        int status = response.status();
        if (status == 403 || status == 404 || status == 405) {
            return Collections.emptyList();
        }
        if (!response.isSuccessful()) {
            throw errorFor(response, "teams");
        }
        Object payload = parse(response, "teams");
        List<JsonObject> entries = new ArrayList<>();
        if (payload instanceof List) {
            for (Object element : (List<?>) payload) {
                if (element instanceof Map) {
                    entries.add(JsonObject.wrap(element));
                }
            }
        } else {
            entries.addAll(JsonObject.wrap(payload).optObjects("items"));
        }
        Map<String, Team> teams = new LinkedHashMap<>();
        for (JsonObject entry : entries) {
            String id = idString(entry.raw("id"));
            if (id == null) {
                continue;
            }
            String name = entry.optNonBlank("name");
            if (name == null) {
                name = entry.optNonBlank("displayName");
            }
            teams.put(id, new Team(
                    id,
                    name == null ? "Team " + id : name,
                    entry.optBoolean("default", false) || entry.optBoolean("isDefault", false)));
        }
        return Collections.unmodifiableList(new ArrayList<>(teams.values()));
    }

    /** {@code GET /v1/assets}, one page at a time. Needs {@code assets:list}. */
    public AssetPage listAssets(AssetQuery query) throws IonException {
        URI uri;
        PageCursor cursor = query.cursor();
        if (cursor != null && cursor.nextUri() != null) {
            uri = cursor.nextUri();
            if (!TileUrls.sameOrigin(apiUrl().toString(), uri.toString())) {
                throw new IonException(IonException.Kind.INVALID_RESPONSE,
                        "Cesium ion returned a next-page link to another host.");
            }
        } else {
            Map<String, String> parameters = new LinkedHashMap<>();
            if (query.search() != null) {
                parameters.put("search", query.search());
            }
            if (query.limit() > 0) {
                parameters.put("limit", Integer.toString(query.limit()));
            }
            if (cursor != null && cursor.pageToken() != null) {
                parameters.put("pageToken", cursor.pageToken());
            }
            uri = api("v1/assets", parameters);
        }

        HttpResponse response = send(uri);
        if (!response.isSuccessful()) {
            throw errorFor(response, "asset list");
        }
        JsonObject json = asObject(parse(response, "asset list"), "asset list");
        List<IonAsset> assets = new ArrayList<>();
        for (JsonObject item : json.optObjects("items")) {
            IonAsset asset = parseAsset(item);
            if (asset != null) {
                assets.add(asset);
            }
        }
        return new AssetPage(assets, nextCursor(json, response));
    }

    /** {@code GET /v1/assets/{id}}. Needs {@code assets:read}. */
    public IonAsset asset(long assetId) throws IonException {
        requireAssetId(assetId);
        IonAsset asset = parseAsset(getObject(api("v1/assets/" + assetId, null), "asset " + assetId));
        if (asset == null) {
            throw new IonException(IonException.Kind.INVALID_RESPONSE,
                    "Cesium ion returned an asset without an id for asset " + assetId + ".");
        }
        return asset;
    }

    /**
     * {@code GET /v1/assets/{id}/endpoint}: where and how to stream the asset.
     * The result carries short-lived credentials; use {@link EndpointCache}
     * while streaming.
     */
    public AssetEndpoint endpoint(long assetId) throws IonException {
        requireAssetId(assetId);
        JsonObject json = getObject(api("v1/assets/" + assetId + "/endpoint", null), "endpoint for asset " + assetId);
        return EndpointParser.parse(assetId, json);
    }

    /** {@code GET /v1/defaults}: global default and quick-add datasets. */
    public Defaults defaults() throws IonException {
        JsonObject json = getObject(api("v1/defaults", null), "defaults");
        JsonObject defaultAssets = json.optObject("defaultAssets");
        List<Defaults.QuickAddAsset> quickAdd = new ArrayList<>();
        for (JsonObject entry : json.optObjects("quickAddAssets")) {
            long id = entry.optLong("assetId", -1);
            if (id > 0) {
                quickAdd.add(new Defaults.QuickAddAsset(
                        id,
                        entry.optNonBlank("name"),
                        entry.optString("description", ""),
                        entry.optString("type", null),
                        entry.optBoolean("subscribed", false)));
            }
        }
        return new Defaults(
                defaultAssets.optLong("imagery", -1),
                defaultAssets.optLong("terrain", -1),
                defaultAssets.optLong("buildings", -1),
                quickAdd);
    }

    static IonAsset parseAsset(JsonObject item) {
        long id = item.optLong("id", -1);
        if (id <= 0) {
            return null;
        }
        String status = item.optString("status", null);
        if (status == null && item.isObject("status")) {
            status = item.optObject("status").optString("type", null);
        }
        return new IonAsset(
                id,
                item.optString("name", null),
                item.optString("description", null),
                item.optString("type", null),
                AssetStatus.fromIon(status),
                (int) item.optLong("percentComplete", 0),
                item.optLong("bytes", 0),
                item.optString("dateAdded", null),
                item.optString("attribution", null));
    }

    private PageCursor nextCursor(JsonObject json, HttpResponse response) throws IonException {
        String token = json.optNonBlank("nextPageToken");
        if (token != null) {
            return PageCursor.ofToken(token);
        }
        for (String link : response.headerValues("Link")) {
            Matcher matcher = LINK_NEXT.matcher(link);
            if (matcher.find()) {
                URI next = response.finalUri().resolve(matcher.group(1).trim());
                if (TileUrls.sameOrigin(apiUrl().toString(), next.toString())) {
                    return PageCursor.ofUri(next);
                }
            }
        }
        return null;
    }

    private URI api(String path, Map<String, String> parameters) throws IonException {
        Map<String, String> query = new LinkedHashMap<>();
        if (parameters != null) {
            query.putAll(parameters);
        }
        if (teamId != null && path.startsWith("v1/assets")) {
            query.put("team", teamId);
        }
        StringBuilder uri = new StringBuilder(apiUrl().resolve(path).toString());
        char separator = '?';
        for (Map.Entry<String, String> parameter : query.entrySet()) {
            uri.append(separator).append(encode(parameter.getKey())).append('=').append(encode(parameter.getValue()));
            separator = '&';
        }
        return URI.create(uri.toString());
    }

    private JsonObject getObject(URI uri, String what) throws IonException {
        HttpResponse response = send(uri);
        if (!response.isSuccessful()) {
            throw errorFor(response, what);
        }
        return asObject(parse(response, what), what);
    }

    private HttpResponse send(URI uri) throws IonException {
        HttpRequest.Builder builder = HttpRequest.get(uri).header("Accept", "application/json");
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken.reveal());
        }
        HttpRequest request = builder.build();
        try {
            return transport.send(request);
        } catch (IOException failure) {
            throw new IonException(IonException.Kind.NETWORK,
                    "Could not reach Cesium ion at " + uri.getHost() + ": " + failure.getMessage(), -1, failure);
        }
    }

    private static Object parse(HttpResponse response, String what) throws IonException {
        try {
            return Json.parse(response.bodyText());
        } catch (JsonException malformed) {
            throw new IonException(IonException.Kind.INVALID_RESPONSE,
                    "Cesium ion returned malformed JSON for the " + what + ": " + malformed.getMessage(),
                    response.status(), malformed);
        }
    }

    private static JsonObject asObject(Object payload, String what) throws IonException {
        if (!(payload instanceof Map)) {
            throw new IonException(IonException.Kind.INVALID_RESPONSE,
                    "Cesium ion returned an unexpected response for the " + what + ".");
        }
        return JsonObject.wrap(payload);
    }

    static IonException errorFor(HttpResponse response, String what) {
        int status = response.status();
        String detail = ionErrorMessage(response);
        String suffix = detail == null ? "" : " (" + detail + ")";
        switch (status) {
            case 401:
                return new IonException(IonException.Kind.UNAUTHORIZED,
                        "Cesium ion rejected the access token. Check the token in the plugin settings." + suffix,
                        status, null);
            case 403:
                return new IonException(IonException.Kind.FORBIDDEN,
                        "The Cesium ion token is not allowed to read the " + what + "." + suffix, status, null);
            case 404:
                return new IonException(IonException.Kind.NOT_FOUND,
                        "Cesium ion could not find the " + what + "." + suffix, status, null);
            case 429:
                return new IonException(IonException.Kind.RATE_LIMITED,
                        "Cesium ion is rate limiting requests; try again shortly." + suffix, status, null);
            default:
                return new IonException(IonException.Kind.SERVER,
                        "Cesium ion request for the " + what + " failed with HTTP " + status + "." + suffix,
                        status, null);
        }
    }

    /** The {@code message} from an ion error body, truncated, or {@code null}. */
    private static String ionErrorMessage(HttpResponse response) {
        try {
            String message = JsonObject.wrap(Json.parse(response.bodyText())).optNonBlank("message");
            if (message == null) {
                return null;
            }
            return message.length() > 300 ? message.substring(0, 300) + "..." : message;
        } catch (JsonException notJson) {
            return null;
        }
    }

    private static String idString(Object raw) {
        if (raw instanceof Long) {
            return raw.toString();
        }
        if (raw instanceof String && !((String) raw).trim().isEmpty()) {
            return ((String) raw).trim();
        }
        return null;
    }

    private static void requireAssetId(long assetId) throws IonException {
        if (assetId <= 0) {
            throw new IonException(IonException.Kind.CONFIGURATION, "A positive Cesium ion asset id is required.");
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public String toString() {
        return "IonClient[" + server + (teamId != null ? ", team=" + teamId : "") + "]";
    }
}
