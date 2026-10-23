package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.cesium.tak.ion.model.AssetEndpoint;
import com.cesium.tak.ion.model.AssetType;
import com.cesium.tak.ion.util.Secret;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class EndpointCacheTest {
    private static final long HOUR = 60L * 60L * 1000L;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AtomicInteger fetches = new AtomicInteger();

    private AssetEndpoint endpoint(long expiresAt) {
        fetches.incrementAndGet();
        return new AssetEndpoint.IonHosted(1, AssetType.TILES_3D, "https://assets.ion.cesium.com/1/tileset.json",
                Collections.emptyMap(), Secret.of("t" + fetches.get()), Collections.emptyList(), expiresAt);
    }

    @Test
    void refreshesBeforeTokenExpiry() throws Exception {
        EndpointCache cache = new EndpointCache(id -> endpoint(now.get() + HOUR), now::get);
        AssetEndpoint first = cache.get(1);
        now.addAndGet(HOUR - EndpointCache.REFRESH_MARGIN_MS - 1);
        assertSame(first, cache.get(1));
        now.addAndGet(2);
        assertNotSame(first, cache.get(1));
        assertEquals(2, fetches.get());
    }

    @Test
    void usesDefaultTtlWithoutExpiry() throws Exception {
        EndpointCache cache = new EndpointCache(id -> endpoint(-1), now::get);
        AssetEndpoint first = cache.get(1);
        now.addAndGet(EndpointCache.DEFAULT_TTL_MS - 1);
        assertSame(first, cache.get(1));
        now.addAndGet(1);
        assertNotSame(first, cache.get(1));
    }

    @Test
    void shortLivedTokensStillCacheBriefly() {
        long start = now.get();
        AssetEndpoint shortLived = endpoint(start + 10_000);
        assertEquals(start + EndpointCache.MIN_TTL_MS, EndpointCache.refreshAt(shortLived, start));
    }

    @Test
    void invalidateIgnoresStaleCopies() throws Exception {
        EndpointCache cache = new EndpointCache(id -> endpoint(-1), now::get);
        AssetEndpoint first = cache.get(1);
        cache.invalidate(1, first);
        AssetEndpoint second = cache.get(1);
        cache.invalidate(1, first); // A late 401 from a request that used the old token.
        assertSame(second, cache.get(1));
        assertEquals(2, fetches.get());
    }

    @Test
    void concurrentRequestsShareOneFetch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        EndpointCache cache = new EndpointCache(id -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return endpoint(-1);
        }, now::get);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) {
            pool.execute(() -> {
                try {
                    cache.get(1);
                } catch (IonException ignored) {
                    // Not thrown by this source.
                }
            });
        }
        Thread.sleep(100);
        release.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        assertEquals(1, fetches.get());
    }
}
