package com.cesium.atak.ion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class MapLayerNamesTest {
    @Test
    public void selectsInTheMobileLayerWhenAtakHasIt() {
        assertEquals(Collections.singletonList("Mobile"), MapLayerNames.selectionCandidates(
                Arrays.asList("Raster Layers", "Native", "Mobile", "Contour Lines Overlay")));
    }

    @Test
    public void otherwiseTriesTheLayersFoundThenTheFallbacksOnce() {
        assertEquals(Arrays.asList("Grid", "Native", "Mobile", "Mobile Imagery", "Imagery"),
                MapLayerNames.selectionCandidates(Arrays.asList("Grid", "Native")));
    }
}
