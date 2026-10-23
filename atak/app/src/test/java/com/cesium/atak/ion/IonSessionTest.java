package com.cesium.atak.ion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import com.cesium.tak.ion.IonClient;
import com.cesium.tak.ion.IonServer;
import com.cesium.tak.ion.http.HttpResponse;
import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.util.Secret;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class IonSessionTest {
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger endpointRequests = new AtomicInteger();

    private IonSession session() {
        return new IonSession(() -> {
            opened.incrementAndGet();
            return new IonClient(IonServer.cloud(), Secret.of("token"), request -> {
                endpointRequests.incrementAndGet();
                String body = "{\"type\":\"3DTILES\",\"url\":\"https://assets.ion.cesium.com/1/tileset.json\","
                        + "\"accessToken\":\"endpoint-token\",\"attributions\":[]}";
                return new HttpResponse(200, request.uri(), Collections.emptyMap(),
                        body.getBytes(StandardCharsets.UTF_8));
            });
        });
    }

    @Test
    public void reusesTheClientAndCachedEndpointsUntilReset() throws Exception {
        IonSession session = session();
        IonClient client = session.client();
        AssetEndpoint first = session.endpoint(1);
        assertSame(client, session.client());
        assertSame(first, session.endpoint(1));
        assertEquals(1, opened.get());
        assertEquals(1, endpointRequests.get());

        session.reset();
        assertNotSame(first, session.endpoint(1));
        assertEquals(2, opened.get());
    }

    @Test
    public void invalidationRefetchesOnlyTheStaleEndpoint() throws Exception {
        IonSession session = session();
        AssetEndpoint first = session.endpoint(1);
        session.invalidate(1, first);
        AssetEndpoint second = session.endpoint(1);
        assertNotSame(first, second);
        session.invalidate(1, first); // stale again: keeps the newer one
        assertSame(second, session.endpoint(1));
        assertEquals(1, opened.get());
    }

    @Test
    public void invalidateBeforeConnectingIsHarmless() {
        session().invalidate(1, null);
        assertEquals(0, opened.get());
    }

}
