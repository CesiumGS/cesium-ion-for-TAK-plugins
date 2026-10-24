package com.cesium.atak.ion;

import static org.junit.Assert.assertEquals;

import com.cesium.tak.ion.model.AssetType;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class LayerNamesTest {
    @Test
    public void layerNamesAreValidFileNames() {
        assertEquals("Cesium ion - Bing Maps Aerial", LayerNames.imageryLayerName("Bing Maps Aerial"));
        assertEquals("Cesium ion - Site A Ortho 2024 v2", LayerNames.imageryLayerName("Site A: Ortho/2024 \"v2\""));
        assertEquals("Cesium ion - imagery", LayerNames.imageryLayerName(" :?* "));
        assertEquals("overlay", LayerNames.safeName("|", "overlay"));
    }

    @Test
    public void vectorExtensionsFollowTypeAndContent() {
        assertEquals(".geojson", LayerNames.vectorExtension(AssetType.GEOJSON, "{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(".kml", LayerNames.vectorExtension(AssetType.KML, "<kml/>".getBytes(StandardCharsets.UTF_8)));
        assertEquals(".kmz", LayerNames.vectorExtension(AssetType.KML, new byte[] {'P', 'K', 3, 4, 0}));
    }

    @Test
    public void recognisesItsOwnImageryNames() {
        org.junit.Assert.assertTrue(LayerNames.isImageryLayerName(LayerNames.imageryLayerName("Sentinel-2")));
        org.junit.Assert.assertFalse(LayerNames.isImageryLayerName("ESRI World Topo"));
        org.junit.Assert.assertFalse(LayerNames.isImageryLayerName(null));
    }
}
