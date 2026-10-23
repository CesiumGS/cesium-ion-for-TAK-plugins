package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cesium.tak.ion.imagery.ImageryResolver;
import com.cesium.tak.ion.imagery.TileTemplate;
import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.model.AssetType;
import com.cesium.tak.ion.util.Secret;

import java.util.Collections;

import org.junit.jupiter.api.Test;

class ImageryResolverTest {
    private final FakeTransport transport = new FakeTransport();
    private final ImageryResolver resolver = new ImageryResolver(transport);

    @Test
    void ionTmsUsesTileMapResourceAndStaysCredentialFree() throws Exception {
        transport.respond("https://assets.ion.cesium.com/3954/tilemapresource.xml?v=2&access_token=T", 200,
                "<TileMap><SRS>EPSG:3857</SRS><TileFormat extension=\"jpg\"/><TileSets profile=\"mercator\">"
                        + "<TileSet order=\"0\"/><TileSet order=\"14\"/></TileSets></TileMap>");
        AssetEndpoint endpoint = new AssetEndpoint.IonHosted(3954, AssetType.IMAGERY,
                "https://assets.ion.cesium.com/3954/?v=2", Collections.emptyMap(), Secret.of("T"),
                Collections.emptyList(), -1);
        TileTemplate template = resolver.resolve(endpoint);
        assertEquals("https://assets.ion.cesium.com/3954/{z}/{x}/{y}.jpg?v=2", template.url());
        assertEquals(TileTemplate.Scheme.TMS, template.scheme());
        assertEquals(14, template.maxZoom());
        assertFalse(template.isGeographic());
        assertTrue(template.needsProxy());
    }

    @Test
    void ionTmsEndpointNamingAFileUsesItsDirectory() throws Exception {
        transport.respond("https://assets.ion.cesium.com/7/tilemapresource.xml?access_token=T", 200,
                "<TileMap><SRS>EPSG:4326</SRS><TileSets profile=\"geodetic\"><TileSet order=\"0\"/></TileSets></TileMap>");
        AssetEndpoint endpoint = new AssetEndpoint.IonHosted(7, AssetType.IMAGERY,
                "https://assets.ion.cesium.com/7/tilemapresource.xml", Collections.emptyMap(), Secret.of("T"),
                Collections.emptyList(), -1);
        TileTemplate template = resolver.resolve(endpoint);
        assertEquals("https://assets.ion.cesium.com/7/{z}/{x}/{y}.png", template.url());
        assertTrue(template.isGeographic());
    }

    @Test
    void bingUsesMetadataAndNeedsNoProxy() throws Exception {
        transport.respond("https://dev.virtualearth.net/REST/v1/Imagery/Metadata/RoadOnDemand?incl=ImageryProviders&uriScheme=https&key=BKEY",
                200, "{\"resourceSets\":[{\"resources\":[{\"imageUrl\":\"http://ecn.{subdomain}.tiles.virtualearth.net/tiles/r{quadkey}.jpeg?g=1&mkt={culture}\","
                        + "\"imageUrlSubdomains\":[\"t0\",\"t1\"],\"zoomMin\":1,\"zoomMax\":20}]}]}");
        AssetEndpoint endpoint = new AssetEndpoint.BingImagery(4, "https://dev.virtualearth.net/", Secret.of("BKEY"),
                "RoadOnDemand", null, Collections.emptyList());
        TileTemplate template = resolver.resolve(endpoint);
        assertEquals("https://ecn.t0.tiles.virtualearth.net/tiles/r{q}.jpeg?g=1&mkt=en-US", template.url());
        assertEquals(TileTemplate.Scheme.QUADKEY, template.scheme());
        assertEquals(20, template.maxZoom());
        assertFalse(template.needsProxy());
        assertFalse(template.url().contains("BKEY"));
    }

    @Test
    void google2dIsProxied() throws Exception {
        TileTemplate template = resolver.resolve(new AssetEndpoint.Google2DImagery(
                9, "https://tile.googleapis.com", Secret.of("S"), Secret.of("K"), Collections.emptyList()));
        assertEquals("https://tile.googleapis.com/v1/2dtiles/{z}/{x}/{y}", template.url());
        assertTrue(template.needsProxy());
    }

    @Test
    void nonImageryIsRejected() {
        AssetEndpoint terrain = new AssetEndpoint.ExternalTerrain(1, "https://t.example.com/", Collections.emptyMap(),
                Collections.emptyList());
        assertEquals(IonException.Kind.CONFIGURATION, assertThrows(IonException.class, () -> resolver.resolve(terrain)).kind());
    }
}
