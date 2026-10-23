package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cesium.tak.ion.vector.GeoJsonToKml;

import org.junit.jupiter.api.Test;

class GeoJsonToKmlTest {
    private static final String PARCELS = "{\"type\":\"FeatureCollection\",\"features\":["
            + "{\"type\":\"Feature\",\"properties\":{\"name\":\"Lot <1> & 2\",\"area\":1200,\"nested\":{\"x\":1}},"
            + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[-81.5,30.6],[-81.4,30.6],[-81.4,30.7],[-81.5,30.6]],"
            + "[[-81.45,30.62],[-81.44,30.62],[-81.44,30.63],[-81.45,30.62]]]}},"
            + "{\"type\":\"Feature\",\"properties\":{\"stroke\":\"#00f\",\"stroke-width\":4,\"fill-opacity\":0.25},"
            + "\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":[[[0,0,99],[1,1]],[[2,2],[3,3]]]}},"
            + "{\"type\":\"Feature\",\"properties\":null,\"geometry\":null}]}";

    @Test
    void floatsEveryPositionAboveTheGroundAndKeepsHoles() {
        String kml = GeoJsonToKml.convert(PARCELS, "CoStar & parcels", 2.5);
        assertTrue(kml.startsWith("<?xml"), kml);
        assertTrue(kml.contains("<name>CoStar &amp; parcels</name>"), kml);
        assertTrue(kml.contains("<altitudeMode>relativeToGround</altitudeMode>"), kml);
        assertTrue(kml.contains("-81.5,30.6,2.5 -81.4,30.6,2.5"), kml);
        assertTrue(kml.contains("<innerBoundaryIs>"), "holes kept");
        assertTrue(kml.contains("0,0,2.5 1,1,2.5"), "input Z replaced by the offset");
        assertFalse(kml.contains(",99"), kml);
        assertTrue(kml.contains("<MultiGeometry>"), kml);
    }

    @Test
    void carriesNamesPropertiesAndSimplestyle() {
        String kml = GeoJsonToKml.convert(PARCELS, "p", 2);
        assertTrue(kml.contains("<name>Lot &lt;1&gt; &amp; 2</name>"), kml);
        assertTrue(kml.contains("<Data name=\"area\"><value>1200</value></Data>"), kml);
        assertFalse(kml.contains("nested"), "objects are not flattened");
        // Default orange (aabbggrr) with a half-transparent fill.
        assertTrue(kml.contains("<LineStyle><color>ff0088ff</color><width>2</width>"), kml);
        assertTrue(kml.contains("<PolyStyle><color>800088ff</color>"), kml);
        // "#00f", width 4, a quarter-opaque fill.
        assertTrue(kml.contains("<LineStyle><color>ffff0000</color><width>4</width>"), kml);
        assertTrue(kml.contains("<PolyStyle><color>400088ff</color>"), kml);
    }

    @Test
    void acceptsBareGeometriesAndRejectsDocumentsWithoutAny() {
        String point = GeoJsonToKml.convert("{\"type\":\"Point\",\"coordinates\":[10,20]}", "pt", 1);
        assertTrue(point.contains("<Point><altitudeMode>relativeToGround</altitudeMode><coordinates>10,20,1"), point);
        assertNull(GeoJsonToKml.convert("{\"type\":\"FeatureCollection\",\"features\":[]}", "empty", 1));
        assertNull(GeoJsonToKml.convert("not json", "bad", 1));
        assertNull(GeoJsonToKml.convert("{\"type\":\"Polygon\",\"coordinates\":[[[\"a\",\"b\"]]]}", "bad", 1));
    }
}
