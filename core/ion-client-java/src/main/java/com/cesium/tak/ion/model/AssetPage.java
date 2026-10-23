package com.cesium.tak.ion.model;

import java.util.Collections;
import java.util.List;

/** One page of {@code GET /v1/assets}. */
public final class AssetPage {
    private final List<IonAsset> items;
    private final PageCursor next;

    public AssetPage(List<IonAsset> items, PageCursor next) {
        this.items = Collections.unmodifiableList(items);
        this.next = next;
    }

    public List<IonAsset> items() {
        return items;
    }

    /** The cursor for the following page, or {@code null} on the last page. */
    public PageCursor next() {
        return next;
    }

    public boolean hasNext() {
        return next != null;
    }
}
