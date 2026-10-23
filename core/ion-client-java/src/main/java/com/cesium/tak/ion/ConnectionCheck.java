package com.cesium.tak.ion;

import com.cesium.tak.ion.model.Profile;

import java.net.URI;

/** Result of {@link IonClient#verify()}: what a "Test connection" button reports. */
public final class ConnectionCheck {
    private final URI apiUrl;
    private final Profile profile;

    ConnectionCheck(URI apiUrl, Profile profile) {
        this.apiUrl = apiUrl;
        this.profile = profile;
    }

    /** The REST API base the client resolved. */
    public URI apiUrl() {
        return apiUrl;
    }

    /**
     * The token owner, or {@code null} when the token lacks the optional
     * {@code profile:read} scope.
     */
    public Profile profile() {
        return profile;
    }
}
