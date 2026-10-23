package com.cesium.tak.ion.model;

import com.cesium.tak.ion.tiles.TileUrls;
import com.cesium.tak.ion.util.Redactor;
import com.cesium.tak.ion.util.Secret;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where and how to stream an asset, from {@code GET /v1/assets/{id}/endpoint}.
 *
 * <p>{@link #url()} never contains credentials, so it is safe to log, persist,
 * or show. Credentials are held separately and re-applied by
 * {@link #authorize(String)} only to requests on the endpoint's own origin.
 */
public abstract class AssetEndpoint {
    /** The variants a platform has to handle. */
    public enum Kind {
        /** Content hosted by ion; requests need the endpoint access token. */
        ION_HOSTED,
        /** 3D Tiles hosted by a third party, e.g. Google Photorealistic 3D Tiles. */
        EXTERNAL_3D_TILES,
        /** Bing Maps imagery; needs a metadata request to obtain the tile template. */
        BING_IMAGERY,
        /** Google Maps 2D tiles through ion. */
        GOOGLE_2D_IMAGERY,
        /** Quantized-mesh terrain hosted outside ion (STK Terrain Server). */
        EXTERNAL_TERRAIN,
        /** An external type the TAK plugins cannot stream. */
        UNSUPPORTED
    }

    private final long assetId;
    private final AssetType type;
    private final String url;
    private final Map<String, Secret> credentials;
    private final List<Attribution> attributions;
    private final long expiresAtEpochMillis;

    AssetEndpoint(
            long assetId,
            AssetType type,
            String url,
            Map<String, Secret> credentials,
            List<Attribution> attributions,
            long expiresAtEpochMillis) {
        this.assetId = assetId;
        this.type = type == null ? AssetType.UNKNOWN : type;
        this.url = url;
        this.credentials = Collections.unmodifiableMap(new LinkedHashMap<>(credentials));
        this.attributions = Collections.unmodifiableList(attributions);
        this.expiresAtEpochMillis = expiresAtEpochMillis;
    }

    public abstract Kind kind();

    public long assetId() {
        return assetId;
    }

    public AssetType type() {
        return type;
    }

    /** The root URL (tileset JSON, terrain base, or imagery base) without credentials. */
    public String url() {
        return url;
    }

    /** Query parameters to add to every request on {@link #url()}'s origin. */
    public Map<String, Secret> credentials() {
        return credentials;
    }

    public List<Attribution> attributions() {
        return attributions;
    }

    /** When the credentials stop working, or {@code -1} when ion does not say. */
    public long expiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    /**
     * Adds this endpoint's credentials to {@code absoluteUrl} when it is on the
     * endpoint's origin; returns any other URL unchanged.
     */
    public String authorize(String absoluteUrl) {
        if (credentials.isEmpty() || !TileUrls.sameOrigin(url, absoluteUrl)) {
            return absoluteUrl;
        }
        return TileUrls.withQueryParameters(absoluteUrl, credentials);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[asset=" + assetId + ", type=" + type.ionName()
                + ", url=" + Redactor.redact(url) + ", credentials=" + credentials.keySet() + "]";
    }

    /** Content hosted by ion. */
    public static final class IonHosted extends AssetEndpoint {
        private final Secret accessToken;

        public IonHosted(
                long assetId,
                AssetType type,
                String url,
                Map<String, Secret> urlCredentials,
                Secret accessToken,
                List<Attribution> attributions,
                long expiresAtEpochMillis) {
            super(assetId, type, url, withAccessToken(urlCredentials, accessToken), attributions, expiresAtEpochMillis);
            this.accessToken = accessToken;
        }

        @Override
        public Kind kind() {
            return Kind.ION_HOSTED;
        }

        /** The short-lived endpoint token (not the user's ion token), or {@code null}. */
        public Secret accessToken() {
            return accessToken;
        }

        private static Map<String, Secret> withAccessToken(Map<String, Secret> credentials, Secret accessToken) {
            Map<String, Secret> merged = new LinkedHashMap<>(credentials);
            if (accessToken != null) {
                // ion accepts the endpoint token as a query parameter as well as
                // a bearer header; the query form survives renderers that do
                // not let callers set headers.
                merged.put("access_token", accessToken);
            }
            return merged;
        }
    }

    /** 3D Tiles hosted outside ion, such as Google Photorealistic 3D Tiles. */
    public static final class External3DTiles extends AssetEndpoint {
        public External3DTiles(
                long assetId,
                AssetType type,
                String url,
                Map<String, Secret> credentials,
                List<Attribution> attributions) {
            super(assetId, type, url, credentials, attributions, -1);
        }

        @Override
        public Kind kind() {
            return Kind.EXTERNAL_3D_TILES;
        }

        /**
         * Whether this is Google Photorealistic 3D Tiles, which carries extra
         * display requirements: the Google logo plus per-tile copyrights (see
         * {@link com.cesium.tak.ion.tiles.AttributionCollector}).
         */
        public boolean isGooglePhotorealistic() {
            String origin = TileUrls.origin(url());
            return origin != null
                    && origin.startsWith("https://tile.googleapis.com:")
                    && TileUrls.path(url()).toLowerCase(Locale.ROOT).startsWith("/v1/3dtiles/");
        }
    }

    /** Bing Maps imagery. */
    public static final class BingImagery extends AssetEndpoint {
        private final String mapStyle;
        private final String culture;

        public BingImagery(
                long assetId,
                String url,
                Secret key,
                String mapStyle,
                String culture,
                List<Attribution> attributions) {
            super(assetId, AssetType.IMAGERY, url, Collections.singletonMap("key", key), attributions, -1);
            this.mapStyle = mapStyle;
            this.culture = culture;
        }

        @Override
        public Kind kind() {
            return Kind.BING_IMAGERY;
        }

        /** E.g. {@code Aerial}, {@code AerialWithLabelsOnDemand}, {@code RoadOnDemand}. */
        public String mapStyle() {
            return mapStyle;
        }

        /** The label culture, or {@code null}. */
        public String culture() {
            return culture;
        }
    }

    /** Google Maps 2D tiles. */
    public static final class Google2DImagery extends AssetEndpoint {
        public Google2DImagery(long assetId, String url, Secret session, Secret key, List<Attribution> attributions) {
            this(assetId, url, session, key, null, attributions);
        }

        /** @param accessToken ion's endpoint token when ion proxies the tiles, or {@code null} */
        public Google2DImagery(long assetId, String url, Secret session, Secret key, Secret accessToken,
                List<Attribution> attributions) {
            super(assetId, AssetType.IMAGERY, url, credentials(session, key, accessToken), attributions, -1);
        }

        @Override
        public Kind kind() {
            return Kind.GOOGLE_2D_IMAGERY;
        }

        /** The XYZ tile template (without credentials) under {@link #url()}. */
        public String tileTemplate() {
            String base = url().endsWith("/") ? url().substring(0, url().length() - 1) : url();
            return base + "/v1/2dtiles/{z}/{x}/{y}";
        }

        private static Map<String, Secret> credentials(Secret session, Secret key, Secret accessToken) {
            Map<String, Secret> credentials = new LinkedHashMap<>();
            credentials.put("session", session);
            credentials.put("key", key);
            if (accessToken != null) {
                credentials.put("access_token", accessToken);
            }
            return credentials;
        }
    }

    /** Quantized-mesh terrain hosted outside ion. */
    public static final class ExternalTerrain extends AssetEndpoint {
        public ExternalTerrain(long assetId, String url, Map<String, Secret> credentials, List<Attribution> attributions) {
            super(assetId, AssetType.TERRAIN, url, credentials, attributions, -1);
        }

        @Override
        public Kind kind() {
            return Kind.EXTERNAL_TERRAIN;
        }
    }

    /** An external endpoint type the TAK plugins cannot stream. */
    public static final class Unsupported extends AssetEndpoint {
        private final String externalType;

        public Unsupported(long assetId, AssetType type, String externalType, List<Attribution> attributions) {
            super(assetId, type, "", Collections.<String, Secret>emptyMap(), attributions, -1);
            this.externalType = externalType;
        }

        @Override
        public Kind kind() {
            return Kind.UNSUPPORTED;
        }

        public String externalType() {
            return externalType;
        }
    }
}
