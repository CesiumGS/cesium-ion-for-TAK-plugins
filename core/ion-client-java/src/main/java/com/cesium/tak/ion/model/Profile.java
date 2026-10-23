package com.cesium.tak.ion.model;

import java.util.Collections;
import java.util.List;

/** The token owner, from {@code GET /v1/me} (requires the {@code profile:read} scope). */
public final class Profile {
    private final long id;
    private final String username;
    private final List<String> scopes;

    public Profile(long id, String username, List<String> scopes) {
        this.id = id;
        this.username = username == null ? "" : username;
        this.scopes = Collections.unmodifiableList(scopes);
    }

    public long id() {
        return id;
    }

    public String username() {
        return username;
    }

    /** Token scopes when ion reports them; may be empty. */
    public List<String> scopes() {
        return scopes;
    }
}
