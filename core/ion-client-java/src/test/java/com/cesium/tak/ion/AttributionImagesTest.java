package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cesium.tak.ion.model.Attribution;

import java.util.List;

import org.junit.jupiter.api.Test;

class AttributionImagesTest {
    private static final String HOST = "assets.ion.cesium.com";

    @Test
    void findsIonHostedLogosWithTheirAltText() {
        Attribution google = new Attribution("<img alt=\"Google\" src=\"https://assets.ion.cesium.com/google-credit.png\""
                + " style=\"vertical-align:-6px\">", false);
        List<Attribution.Image> images = google.images(HOST);
        assertEquals(1, images.size());
        assertEquals("https://assets.ion.cesium.com/google-credit.png", images.get(0).source);
        assertEquals("Google", images.get(0).alt);
    }

    @Test
    void ignoresImagesFromOtherHostsLookalikesAndPlainHttp() {
        Attribution mixed = new Attribution("<span><IMG SRC='https://evil.example.com/x.png'>"
                + "<img src=\"https://assets.ion.cesium.com.evil.example.com/y.png\">"
                + "<img src=\"http://assets.ion.cesium.com/z.png\"><img alt=\"no source\">"
                + "<a href=\"https://cesium.com\"><img src=\"https://assets.ion.cesium.com/ion-credit.png\" alt=\"Cesium ion\"></a>"
                + "</span>", false);
        List<Attribution.Image> images = mixed.images(HOST);
        assertEquals(1, images.size());
        assertEquals("Cesium ion", images.get(0).alt);
        assertTrue(new Attribution("© OpenStreetMap", false).images(HOST).isEmpty());
    }
}
