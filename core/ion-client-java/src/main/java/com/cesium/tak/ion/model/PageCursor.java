package com.cesium.tak.ion.model;

import java.net.URI;

/**
 * Opaque position in a paged ion listing. Ion versions page either with a
 * {@code nextPageToken} body field or an RFC 8288 {@code Link: rel="next"}
 * header; callers do not need to know which.
 */
public final class PageCursor {
    private final String pageToken;
    private final URI nextUri;

    private PageCursor(String pageToken, URI nextUri) {
        this.pageToken = pageToken;
        this.nextUri = nextUri;
    }

    public static PageCursor ofToken(String pageToken) {
        return new PageCursor(pageToken, null);
    }

    public static PageCursor ofUri(URI nextUri) {
        return new PageCursor(null, nextUri);
    }

    /** The {@code pageToken} query value, or {@code null}. */
    public String pageToken() {
        return pageToken;
    }

    /** The absolute next-page URI, or {@code null}. */
    public URI nextUri() {
        return nextUri;
    }
}
