package com.cesium.tak.ion.imagery;

import com.cesium.tak.ion.IonException;
import com.cesium.tak.ion.http.HttpRequest;
import com.cesium.tak.ion.http.HttpResponse;
import com.cesium.tak.ion.http.HttpTransport;
import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonException;
import com.cesium.tak.ion.json.JsonObject;
import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.tiles.TileUrls;
import com.cesium.tak.ion.tiles.TmsMetadata;
import com.cesium.tak.ion.util.Redactor;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Turns an imagery {@link AssetEndpoint} into a {@link TileTemplate}: ion's
 * own TMS tiles, Bing (via its metadata service), or Google 2D tiles.
 */
public final class ImageryResolver {
    private final HttpTransport transport;

    public ImageryResolver(HttpTransport transport) {
        this.transport = transport;
    }

    public TileTemplate resolve(AssetEndpoint endpoint) throws IonException {
        switch (endpoint.kind()) {
            case ION_HOSTED:
                return ionTms(endpoint);
            case BING_IMAGERY:
                return bing((AssetEndpoint.BingImagery) endpoint);
            case GOOGLE_2D_IMAGERY:
                return google2d((AssetEndpoint.Google2DImagery) endpoint);
            default:
                throw new IonException(IonException.Kind.CONFIGURATION,
                        "Asset " + endpoint.assetId() + " is not streamable imagery (" + endpoint.kind() + ").");
        }
    }

    /** ion-hosted imagery is TMS; {@code tilemapresource.xml} gives format, zooms, and tiling scheme. */
    TileTemplate ionTms(AssetEndpoint endpoint) throws IonException {
        String base = directory(endpoint.url());
        TmsMetadata metadata;
        HttpResponse response = get(endpoint.authorize(TileUrls.deriveChild(base, "tilemapresource.xml")), endpoint);
        if (!response.isSuccessful()) {
            throw new IonException(IonException.Kind.SERVER,
                    "TMS metadata for asset " + endpoint.assetId() + " returned HTTP " + response.status() + ".",
                    response.status(), null);
        }
        try {
            metadata = TmsMetadata.parse(response.body());
        } catch (IllegalArgumentException invalid) {
            throw new IonException(IonException.Kind.INVALID_RESPONSE,
                    "TMS metadata for asset " + endpoint.assetId() + " is not usable: " + invalid.getMessage(), -1, invalid);
        }
        String template = TileUrls.withoutQueryParameters(
                TileUrls.deriveChild(base, "{z}/{x}/{y}." + metadata.extension()),
                endpoint.credentials().keySet());
        return new TileTemplate(template, TileTemplate.Scheme.TMS, metadata.minZoom(), metadata.maxZoom(),
                metadata.extension(), metadata.isGeographic(), true);
    }

    /** Bing tile URLs carry no key, so the renderer can fetch them directly. */
    TileTemplate bing(AssetEndpoint.BingImagery endpoint) throws IonException {
        String metadataUrl = endpoint.url().replaceAll("/+$", "") + "/REST/v1/Imagery/Metadata/"
                + endpoint.mapStyle() + "?incl=ImageryProviders&uriScheme=https";
        HttpResponse response = get(endpoint.authorize(metadataUrl), endpoint);
        if (!response.isSuccessful()) {
            throw new IonException(IonException.Kind.SERVER,
                    "Bing imagery metadata returned HTTP " + response.status() + ".", response.status(), null);
        }
        JsonObject resource;
        try {
            List<JsonObject> sets = Json.parseObject(response.bodyText()).optObjects("resourceSets");
            List<JsonObject> resources = sets.isEmpty() ? java.util.Collections.<JsonObject>emptyList()
                    : sets.get(0).optObjects("resources");
            resource = resources.isEmpty() ? null : resources.get(0);
        } catch (JsonException malformed) {
            throw new IonException(IonException.Kind.INVALID_RESPONSE, "Bing imagery metadata is malformed.", -1, malformed);
        }
        String imageUrl = resource == null ? null : resource.optNonBlank("imageUrl");
        if (imageUrl == null) {
            throw new IonException(IonException.Kind.INVALID_RESPONSE, "Bing imagery metadata has no tile URL.");
        }
        List<String> subdomains = resource.optStrings("imageUrlSubdomains");
        String culture = endpoint.culture() == null ? "en-US" : endpoint.culture();
        String template = imageUrl
                .replace("{subdomain}", subdomains.isEmpty() ? "t0" : subdomains.get(0))
                .replace("{quadkey}", "{q}")
                .replace("{culture}", culture)
                .replaceFirst("^http:", "https:");
        String path = TileUrls.path(template).toLowerCase(Locale.ROOT);
        String extension = path.endsWith(".png") ? "png" : "jpg";
        return new TileTemplate(template, TileTemplate.Scheme.QUADKEY,
                (int) resource.optLong("zoomMin", 1), (int) resource.optLong("zoomMax", 21), extension, false, false);
    }

    /** Google 2D tiles need the session and key on every request: proxied. */
    TileTemplate google2d(AssetEndpoint.Google2DImagery endpoint) {
        return new TileTemplate(endpoint.tileTemplate(), TileTemplate.Scheme.XYZ, 0, 22, "png", false, true);
    }

    private HttpResponse get(String url, AssetEndpoint endpoint) throws IonException {
        try {
            return transport.send(HttpRequest.get(URI.create(url)).build());
        } catch (IOException | IllegalArgumentException failure) {
            throw new IonException(IonException.Kind.NETWORK,
                    "Could not reach imagery service for asset " + endpoint.assetId() + ": "
                            + Redactor.redact(String.valueOf(failure.getMessage())), -1, failure);
        }
    }

    private static String directory(String url) {
        String path = TileUrls.path(url);
        if (path.endsWith("/")) {
            return url;
        }
        // An endpoint naming a file (e.g. tilemapresource.xml) means its
        // directory; keep the query, which RFC 3986 "./" resolution drops.
        String directory = TileUrls.resolve(TileUrls.withoutFragment(url), "./");
        int query = TileUrls.withoutFragment(url).indexOf('?');
        return query >= 0 ? directory + TileUrls.withoutFragment(url).substring(query) : directory;
    }
}
