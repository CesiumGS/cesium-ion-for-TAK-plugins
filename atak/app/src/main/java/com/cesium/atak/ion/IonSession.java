package com.cesium.atak.ion;

import com.cesium.tak.ion.EndpointCache;
import com.cesium.tak.ion.IonClient;
import com.cesium.tak.ion.IonException;
import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.proxy.TileProxy;

/**
 * The current ion connection: one {@link IonClient} (so Self-Hosted API
 * discovery runs once) and its {@link EndpointCache}, rebuilt by
 * {@link #reset()} when the settings or the saved token change.
 *
 * <p>Also the tile proxy's endpoint source, so tile requests reuse cached
 * endpoints and refresh them when ion rejects their credentials.
 */
final class IonSession implements TileProxy.EndpointSource {
    /** Opens a client from the current settings and saved token. */
    interface Connector {
        IonClient open() throws IonException;
    }

    private final Connector connector;
    private IonClient client;
    private EndpointCache endpoints;

    IonSession(Connector connector) {
        this.connector = connector;
    }

    synchronized IonClient client() throws IonException {
        if (client == null) {
            client = connector.open();
            endpoints = new EndpointCache(client);
        }
        return client;
    }

    /** Forgets the client and cached endpoints; the next call reconnects. */
    synchronized void reset() {
        client = null;
        endpoints = null;
    }

    private synchronized EndpointCache endpoints() throws IonException {
        client();
        return endpoints;
    }

    @Override
    public AssetEndpoint endpoint(long assetId) throws IonException {
        // Fetch outside the session lock so one slow asset does not stall others.
        return endpoints().get(assetId);
    }

    @Override
    public void invalidate(long assetId, AssetEndpoint stale) {
        EndpointCache current;
        synchronized (this) {
            current = endpoints;
        }
        if (current != null) {
            current.invalidate(assetId, stale);
        }
    }
}
