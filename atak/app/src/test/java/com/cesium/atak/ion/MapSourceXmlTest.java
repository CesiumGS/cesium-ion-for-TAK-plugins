package com.cesium.atak.ion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cesium.tak.ion.imagery.TileTemplate;

import org.junit.Test;

public class MapSourceXmlTest {
    @Test
    public void ionTmsThroughTheProxy() {
        TileTemplate template = new TileTemplate("https://assets.ion.cesium.com/3954/{z}/{x}/{y}.jpg",
                TileTemplate.Scheme.TMS, 0, 16, "jpg", true, true);
        String xml = MapSourceXml.xml("Cesium ion - Sentinel-2", template,
                "http://127.0.0.1:38735/SECRET/a/3954/3954/{z}/{x}/{y}.jpg");
        assertTrue(xml, xml.contains("<url>http://127.0.0.1:38735/SECRET/a/3954/3954/{$z}/{$x}/{$y}.jpg</url>"));
        assertTrue(xml.contains("<invertYCoordinate>true</invertYCoordinate>"));
        assertTrue(xml.contains("<coordinatesystem>EPSG:4326</coordinatesystem>"));
        assertTrue(xml.contains("<minZoom>0</minZoom>"));
        assertTrue(xml.contains("<maxZoom>16</maxZoom>"));
        assertTrue(xml.contains("<tileType>jpg</tileType>"));
    }

    @Test
    public void bingQuadkeysAndEscaping() {
        TileTemplate template = new TileTemplate("https://ecn.t0.tiles.virtualearth.net/tiles/a{q}.jpeg?g=1&n=z",
                TileTemplate.Scheme.QUADKEY, 1, 21, "jpg", false, false);
        String xml = MapSourceXml.xml("Bing <Aerial> & \"labels\"", template, template.url());
        assertTrue(xml, xml.contains("<url>https://ecn.t0.tiles.virtualearth.net/tiles/a{$q}.jpeg?g=1&amp;n=z</url>"));
        assertTrue(xml.contains("<name>Bing &lt;Aerial&gt; &amp; &quot;labels&quot;</name>"));
        assertFalse(xml.contains("invertYCoordinate"));
        assertFalse(xml.contains("coordinatesystem"));
    }

    @Test
    public void fileNamesAreStable() {
        assertEquals("cesium-ion-2.xml", MapSourceXml.fileName(2));
    }
}
