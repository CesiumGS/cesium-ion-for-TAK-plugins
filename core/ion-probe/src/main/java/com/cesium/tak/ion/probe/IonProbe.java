package com.cesium.tak.ion.probe;

import com.cesium.tak.ion.AssetQuery;
import com.cesium.tak.ion.ConnectionCheck;
import com.cesium.tak.ion.IonClient;
import com.cesium.tak.ion.IonException;
import com.cesium.tak.ion.IonServer;
import com.cesium.tak.ion.http.HttpRequest;
import com.cesium.tak.ion.http.HttpResponse;
import com.cesium.tak.ion.http.HttpTransport;
import com.cesium.tak.ion.http.UrlConnectionTransport;
import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonException;
import com.cesium.tak.ion.json.JsonObject;
import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.model.AssetPage;
import com.cesium.tak.ion.model.Attribution;
import com.cesium.tak.ion.model.Defaults;
import com.cesium.tak.ion.model.IonAsset;
import com.cesium.tak.ion.model.Team;
import com.cesium.tak.ion.tiles.AttributionCollector;
import com.cesium.tak.ion.tiles.TileContent;
import com.cesium.tak.ion.tiles.TileUrls;
import com.cesium.tak.ion.tiles.TilesetUriRewriter;
import com.cesium.tak.ion.tiles.TmsMetadata;
import com.cesium.tak.ion.util.Redactor;
import com.cesium.tak.ion.util.Secret;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Desktop diagnostics for the shared ion core. Run with no arguments for help.
 *
 * <p>The ion token is read from {@code CESIUM_ION_TOKEN} or prompted for; it is
 * never accepted as a command-line argument (which would expose it in shell
 * history and process listings) and never printed.
 */
public final class IonProbe {
    static final long GOOGLE_PHOTOREALISTIC_ASSET_ID = 2275207L;

    private final PrintStream out = System.out;
    private final Map<String, String> options = new LinkedHashMap<>();
    private final List<String> positional = new ArrayList<>();

    public static void main(String[] args) {
        System.exit(new IonProbe().run(args));
    }

    int run(String[] args) {
        if (!parseArguments(args) || positional.isEmpty() || positional.get(0).equals("help")) {
            usage();
            return positional.isEmpty() || positional.get(0).equals("help") ? 0 : 2;
        }
        String command = positional.get(0);
        try {
            IonClient client = client();
            switch (command) {
                case "check":
                    return check(client);
                case "teams":
                    return teams(client);
                case "assets":
                    return assets(client);
                case "defaults":
                    return defaults(client);
                case "endpoint":
                    return endpoint(client, assetIdArgument(-1));
                case "tiles":
                    return tiles(client, assetIdArgument(-1));
                case "google":
                    return tiles(client, assetIdArgument(GOOGLE_PHOTOREALISTIC_ASSET_ID));
                default:
                    System.err.println("Unknown command: " + command);
                    usage();
                    return 2;
            }
        } catch (IonException failure) {
            System.err.println("ion error (" + failure.kind() + "): " + failure.getMessage());
            return 1;
        } catch (IllegalArgumentException invalid) {
            System.err.println(Redactor.redact(invalid.getMessage()));
            return 2;
        }
    }

    private int check(IonClient client) throws IonException {
        ConnectionCheck check = client.verify();
        out.println("Server:     " + client.server());
        out.println("API:        " + check.apiUrl());
        if (check.profile() != null) {
            out.println("User:       " + check.profile().username() + " (id " + check.profile().id() + ")");
            if (!check.profile().scopes().isEmpty()) {
                out.println("Scopes:     " + String.join(" ", check.profile().scopes()));
            }
        } else {
            out.println("User:       (token has no profile:read scope; that is fine)");
        }
        out.println("Assets:     listable");
        out.println("OK");
        return 0;
    }

    private int teams(IonClient client) throws IonException {
        List<Team> teams = client.teams();
        if (teams.isEmpty()) {
            out.println("No team workspaces (personal workspace only).");
        }
        for (Team team : teams) {
            out.printf("%-12s %s%s%n", team.id(), team.name(), team.isDefault() ? "  [default]" : "");
        }
        return 0;
    }

    private int assets(IonClient client) throws IonException {
        AssetQuery query = AssetQuery.all().withSearch(options.get("search"));
        boolean all = options.containsKey("all");
        int pages = 0;
        int count = 0;
        out.printf("%-10s %-9s %-14s %-9s %s%n", "ID", "TYPE", "STATUS", "TAK", "NAME");
        while (true) {
            AssetPage page = client.listAssets(query);
            pages++;
            for (IonAsset asset : page.items()) {
                count++;
                out.printf("%-10d %-9s %-14s %-9s %s%n",
                        asset.id(),
                        asset.rawType(),
                        asset.status() + (asset.percentComplete() > 0 && asset.percentComplete() < 100
                                ? " " + asset.percentComplete() + "%" : ""),
                        asset.isLoadable() ? "yes" : "-",
                        asset.name());
            }
            if (!page.hasNext() || !all) {
                if (page.hasNext()) {
                    out.println("(more assets available; add --all)");
                }
                break;
            }
            if (pages >= 50) {
                out.println("(stopped after 50 pages)");
                break;
            }
            query = query.after(page.next());
        }
        out.println(count + " asset(s), " + pages + " page(s).");
        return 0;
    }

    private int defaults(IonClient client) throws IonException {
        Defaults defaults = client.defaults();
        out.println("Default imagery:   " + defaults.imageryAssetId());
        out.println("Default terrain:   " + defaults.terrainAssetId());
        out.println("Default buildings: " + defaults.buildingsAssetId());
        out.println("Quick-add datasets:");
        for (Defaults.QuickAddAsset asset : defaults.quickAddAssets()) {
            out.printf("  %-10d %-9s %-12s %s%n", asset.assetId(), asset.type().ionName(),
                    asset.isSubscribed() ? "in account" : "not added", asset.name());
        }
        return 0;
    }

    private int endpoint(IonClient client, long assetId) throws IonException {
        AssetEndpoint endpoint = client.endpoint(assetId);
        describe(endpoint);
        if (endpoint.kind() == AssetEndpoint.Kind.ION_HOSTED && endpoint.type().ionName().equals("IMAGERY")) {
            describeTms(endpoint);
        }
        return 0;
    }

    private void describe(AssetEndpoint endpoint) {
        out.println("Asset:        " + endpoint.assetId());
        out.println("Kind:         " + endpoint.kind());
        out.println("Type:         " + endpoint.type().ionName());
        out.println("URL:          " + Redactor.redact(endpoint.url()));
        out.println("Credentials:  " + (endpoint.credentials().isEmpty()
                ? "none" : String.join(", ", endpoint.credentials().keySet()) + " (values hidden)"));
        if (endpoint.expiresAtEpochMillis() > 0) {
            long minutes = (endpoint.expiresAtEpochMillis() - System.currentTimeMillis()) / 60_000L;
            out.println("Expires in:   " + minutes + " min");
        }
        if (endpoint instanceof AssetEndpoint.External3DTiles) {
            out.println("Google P3DT:  " + ((AssetEndpoint.External3DTiles) endpoint).isGooglePhotorealistic());
        }
        if (endpoint instanceof AssetEndpoint.BingImagery) {
            out.println("Map style:    " + ((AssetEndpoint.BingImagery) endpoint).mapStyle());
        }
        if (endpoint instanceof AssetEndpoint.Unsupported) {
            out.println("External:     " + ((AssetEndpoint.Unsupported) endpoint).externalType() + " (not streamable in TAK)");
        }
        for (Attribution attribution : endpoint.attributions()) {
            String text = attribution.plainText();
            out.println("Attribution:  " + (text.isEmpty() ? "(logo/image) " + Redactor.redact(attribution.html()) : text));
        }
    }

    private void describeTms(AssetEndpoint endpoint) {
        String metadataUrl = TileUrls.resolve(endpoint.url().endsWith("/") ? endpoint.url() : endpoint.url() + "/",
                "tilemapresource.xml");
        try {
            HttpResponse response = transport().send(HttpRequest.get(URI.create(endpoint.authorize(metadataUrl))).build());
            if (!response.isSuccessful()) {
                out.println("TMS metadata: HTTP " + response.status());
                return;
            }
            TmsMetadata tms = TmsMetadata.parse(response.body());
            out.println("TMS:          " + tms.srs() + " profile=" + tms.profile() + " ." + tms.extension()
                    + " zoom " + tms.minZoom() + "-" + tms.maxZoom()
                    + (tms.isGeographic() ? " (geographic)" : " (web mercator)"));
        } catch (IOException | IllegalArgumentException failure) {
            out.println("TMS metadata: " + Redactor.redact(String.valueOf(failure.getMessage())));
        }
    }

    /**
     * Walks a 3D Tiles hierarchy the way a renderer would (same-origin query
     * inheritance, credentials applied per request) and reports what a TAK
     * renderer must support to draw it.
     */
    private int tiles(IonClient client, long assetId) throws IonException {
        AssetEndpoint endpoint = client.endpoint(assetId);
        describe(endpoint);
        if (endpoint.kind() != AssetEndpoint.Kind.ION_HOSTED && endpoint.kind() != AssetEndpoint.Kind.EXTERNAL_3D_TILES) {
            System.err.println("Asset " + assetId + " is not streamed as 3D Tiles.");
            return 1;
        }
        if (!endpoint.type().ionName().equals("3DTILES")) {
            System.err.println("Asset " + assetId + " is " + endpoint.type().ionName() + ", not 3D Tiles.");
            return 1;
        }
        int maxRequests = intOption("max-requests", 12);
        int maxDepth = intOption("depth", 6);
        out.println();
        out.println("Walking up to " + maxRequests + " requests, depth " + maxDepth + ":");

        HttpTransport transport = transport();
        AttributionCollector credits = new AttributionCollector();
        TreeSet<String> tilesetVersions = new TreeSet<>();
        TreeSet<String> tilesetExtensions = new TreeSet<>();
        TreeSet<String> gltfUsed = new TreeSet<>();
        TreeSet<String> gltfRequired = new TreeSet<>();
        TreeSet<String> formats = new TreeSet<>();
        boolean implicitTiling = false;
        double largestGeometricError = Double.NaN;
        int failures = 0;

        Deque<String[]> queue = new ArrayDeque<>();
        queue.add(new String[] {endpoint.url(), "0"});
        int requests = 0;
        while (!queue.isEmpty() && requests < maxRequests) {
            String[] next = queue.poll();
            String url = next[0];
            int depth = Integer.parseInt(next[1]);
            requests++;
            HttpResponse response;
            try {
                response = transport.send(HttpRequest.get(URI.create(endpoint.authorize(url))).build());
            } catch (IOException | IllegalArgumentException failure) {
                failures++;
                out.printf("  [%d] ERROR %s: %s%n", depth, Redactor.redact(url), Redactor.redact(String.valueOf(failure.getMessage())));
                continue;
            }
            TileContent.Format format = TileContent.sniff(response.body());
            out.printf("  [%d] %s %-7s %8d B  %s%n", depth, response.status(), format, response.body().length,
                    Redactor.redact(TileUrls.path(url)));
            if (!response.isSuccessful()) {
                failures++;
                if (response.status() == 401 || response.status() == 403) {
                    out.println("      credentials rejected; the endpoint token may have expired or the asset is not in the account");
                }
                continue;
            }
            formats.add(format.name());

            if (format == TileContent.Format.JSON) {
                try {
                    JsonObject tileset = Json.parseObject(response.bodyText());
                    String version = tileset.optObject("asset").optString("version", null);
                    if (version != null) {
                        tilesetVersions.add(version);
                    }
                    tilesetExtensions.addAll(tileset.optStrings("extensionsUsed"));
                    TilesetUriRewriter.Result scan = TilesetUriRewriter.rewrite(response.bodyText(), reference -> null);
                    List<String> references = scan.references();
                    if (!Double.isNaN(scan.maxGeometricError())) {
                        largestGeometricError = Double.isNaN(largestGeometricError)
                                ? scan.maxGeometricError()
                                : Math.max(largestGeometricError, scan.maxGeometricError());
                        out.println("      max geometricError " + scan.maxGeometricError()
                                + ", " + references.size() + " references");
                    }
                    // Follow one nested tileset and one content tile per document,
                    // which reaches leaf content quickly without bulk downloading.
                    String json = null;
                    String content = null;
                    for (String reference : references) {
                        if (reference.contains("{")) {
                            implicitTiling = true;
                            continue;
                        }
                        String path = TileUrls.path(reference).toLowerCase();
                        if (path.endsWith(".json")) {
                            json = json == null ? reference : json;
                        } else if (content == null) {
                            content = reference;
                        }
                    }
                    if (depth < maxDepth) {
                        if (content != null) {
                            queue.addFirst(new String[] {TileUrls.deriveChild(url, content), Integer.toString(depth + 1)});
                        }
                        if (json != null) {
                            queue.add(new String[] {TileUrls.deriveChild(url, json), Integer.toString(depth + 1)});
                        }
                    }
                } catch (JsonException malformed) {
                    out.println("      malformed tileset JSON: " + malformed.getMessage());
                    failures++;
                }
            } else {
                TileContent.GltfInfo gltf = TileContent.gltfInfo(response.body());
                if (gltf != null) {
                    gltfUsed.addAll(gltf.extensionsUsed());
                    gltfRequired.addAll(gltf.extensionsRequired());
                    credits.record(gltf.copyrightEntries());
                    out.println("      glTF " + gltf.version() + " required=" + gltf.extensionsRequired()
                            + (gltf.copyright().isEmpty() ? "" : " copyright=\"" + gltf.copyright() + "\""));
                }
            }
        }

        out.println();
        out.println("Summary for asset " + assetId + ":");
        out.println("  Requests:                " + requests + " (" + failures + " failed)");
        out.println("  3D Tiles versions:       " + (tilesetVersions.isEmpty() ? "?" : String.join(", ", tilesetVersions)));
        out.println("  Implicit tiling:         " + (implicitTiling ? "yes (renderer must support 3D Tiles 1.1 implicit tiling)" : "not seen"));
        out.println("  Tileset extensions:      " + (tilesetExtensions.isEmpty() ? "none" : String.join(", ", tilesetExtensions)));
        out.println("  Content formats:         " + String.join(", ", formats));
        if (!Double.isNaN(largestGeometricError)) {
            out.println("  Largest geometricError:  " + largestGeometricError
                    + (largestGeometricError > 1e7 ? " (sentinel; ATAK needs the proxy's geometricError ceiling)" : ""));
        }
        out.println("  glTF extensions used:    " + (gltfUsed.isEmpty() ? "none" : String.join(", ", gltfUsed)));
        out.println("  glTF extensions REQUIRED:" + (gltfRequired.isEmpty() ? " none" : " " + String.join(", ", gltfRequired)));
        if (!credits.entries().isEmpty()) {
            out.println("  Tile credits (must be displayed): " + credits.creditLine());
        }
        if (endpoint instanceof AssetEndpoint.External3DTiles
                && ((AssetEndpoint.External3DTiles) endpoint).isGooglePhotorealistic()) {
            out.println("  Google display rules: show the Google logo and the tile credits above while tiles are visible.");
        }
        return failures == 0 ? 0 : 1;
    }

    private IonClient client() throws IonException {
        IonServer server = options.containsKey("server")
                ? IonServer.selfHosted(options.get("server"), options.get("api"), options.containsKey("allow-http"))
                : IonServer.cloud();
        Secret token;
        if (options.containsKey("single-user")) {
            if (server.kind() == IonServer.Kind.CLOUD) {
                throw new IllegalArgumentException("--single-user applies only to a Self-Hosted --server.");
            }
            token = null;
        } else {
            token = readToken();
        }
        IonClient client = new IonClient(server, token, transport());
        return options.containsKey("team") ? client.forTeam(options.get("team")) : client;
    }

    private HttpTransport cachedTransport;

    private HttpTransport transport() {
        if (cachedTransport == null) {
            cachedTransport = new UrlConnectionTransport();
        }
        return cachedTransport;
    }

    private static Secret readToken() {
        String fromEnvironment = System.getenv("CESIUM_ION_TOKEN");
        if (fromEnvironment != null && !fromEnvironment.trim().isEmpty()) {
            return Secret.of(fromEnvironment);
        }
        Console console = System.console();
        if (console != null) {
            char[] typed = console.readPassword("Cesium ion access token (input hidden): ");
            if (typed != null && typed.length > 0) {
                return Secret.of(new String(typed));
            }
        } else {
            System.err.print("Cesium ion access token: ");
            try {
                String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                if (line != null && !line.trim().isEmpty()) {
                    return Secret.of(line);
                }
            } catch (IOException ignored) {
                // Fall through to the error below.
            }
        }
        throw new IllegalArgumentException("A Cesium ion access token is required (set CESIUM_ION_TOKEN).");
    }

    private boolean parseArguments(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String name = arg.substring(2);
                if (name.equals("token")) {
                    System.err.println("Refusing --token: pass the token through CESIUM_ION_TOKEN or the prompt.");
                    return false;
                }
                if (name.equals("all") || name.equals("allow-http") || name.equals("single-user")) {
                    options.put(name, "true");
                } else if (i + 1 < args.length) {
                    options.put(name, args[++i]);
                } else {
                    System.err.println("Missing value for " + arg);
                    return false;
                }
            } else {
                positional.add(arg);
            }
        }
        return true;
    }

    private long assetIdArgument(long fallback) {
        if (positional.size() < 2) {
            if (fallback > 0) {
                return fallback;
            }
            throw new IllegalArgumentException("An asset id is required.");
        }
        try {
            return Long.parseLong(positional.get(1));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Not an asset id: " + positional.get(1));
        }
    }

    private int intOption(String name, int fallback) {
        String value = options.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("--" + name + " needs a number.");
        }
    }

    private void usage() {
        out.println("ion-probe: check Cesium ion connectivity and streaming from a desktop.");
        out.println();
        out.println("Token: set CESIUM_ION_TOKEN (e.g. `read -s CESIUM_ION_TOKEN; export CESIUM_ION_TOKEN`),");
        out.println("       or type it at the hidden prompt. Never pass it as an argument.");
        out.println();
        out.println("Commands:");
        out.println("  check                     resolve the API and verify the token");
        out.println("  teams                     list team workspaces");
        out.println("  assets [--search TEXT] [--all]");
        out.println("                            list assets (TAK column: streamable in TAK)");
        out.println("  defaults                  list global default and quick-add datasets");
        out.println("  endpoint ASSET_ID         show how an asset streams (credentials hidden)");
        out.println("  tiles ASSET_ID [--depth N] [--max-requests N]");
        out.println("                            walk a 3D Tiles asset and report renderer requirements");
        out.println("  google [ASSET_ID]         'tiles' for Google Photorealistic 3D Tiles (default " + GOOGLE_PHOTOREALISTIC_ASSET_ID + ")");
        out.println();
        out.println("Options:");
        out.println("  --server URL              Cesium ion Self-Hosted URL (default: Cesium ion Cloud)");
        out.println("  --api URL                 Self-Hosted REST API URL, if config.json discovery fails");
        out.println("  --allow-http              permit http:// for a Self-Hosted lab server");
        out.println("  --single-user             Self-Hosted server in single-user mode (no token)");
        out.println("  --team ID                 use a team workspace");
    }
}
