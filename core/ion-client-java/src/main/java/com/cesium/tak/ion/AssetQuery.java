package com.cesium.tak.ion;

import com.cesium.tak.ion.model.PageCursor;

/** Parameters for {@link IonClient#listAssets(AssetQuery)}. */
public final class AssetQuery {
    private final String search;
    private final int limit;
    private final PageCursor cursor;

    private AssetQuery(String search, int limit, PageCursor cursor) {
        this.search = search;
        this.limit = limit;
        this.cursor = cursor;
    }

    public static AssetQuery all() {
        return new AssetQuery(null, 0, null);
    }

    /** Server-side name search. */
    public AssetQuery withSearch(String search) {
        String trimmed = search == null ? null : search.trim();
        return new AssetQuery(trimmed == null || trimmed.isEmpty() ? null : trimmed, limit, cursor);
    }

    /** Page size; {@code 0} lets the server choose. */
    public AssetQuery withLimit(int limit) {
        return new AssetQuery(search, Math.max(0, limit), cursor);
    }

    /** Continue from a previous page's {@link com.cesium.tak.ion.model.AssetPage#next()}. */
    public AssetQuery after(PageCursor cursor) {
        return new AssetQuery(search, limit, cursor);
    }

    public String search() {
        return search;
    }

    public int limit() {
        return limit;
    }

    public PageCursor cursor() {
        return cursor;
    }
}
