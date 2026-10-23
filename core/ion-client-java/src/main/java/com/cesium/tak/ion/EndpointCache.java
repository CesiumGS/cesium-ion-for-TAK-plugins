package com.cesium.tak.ion;

import com.cesium.tak.ion.model.AssetEndpoint;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Keeps asset endpoints (and their short-lived credentials) fresh for a
 * streaming session.
 *
 * <p>Ion endpoint tokens expire after about an hour. Entries refresh ahead of
 * the token's JWT {@code exp}; endpoints without an expiry refresh after
 * {@link #DEFAULT_TTL_MS}. Thread-safe; concurrent requests for one asset
 * share a single fetch.
 */
public final class EndpointCache {
    /** Matches the prototype's proven refresh interval for external endpoints. */
    public static final long DEFAULT_TTL_MS = 30L * 60L * 1000L;
    /** Refresh this long before a token's stated expiry. */
    public static final long REFRESH_MARGIN_MS = 5L * 60L * 1000L;
    /** Never cache for less than this, even for oddly short-lived tokens. */
    public static final long MIN_TTL_MS = 30L * 1000L;

    /** Fetches a fresh endpoint; usually {@link IonClient#endpoint(long)}. */
    public interface Source {
        AssetEndpoint fetch(long assetId) throws IonException;
    }

    /** Milliseconds since the epoch; injectable for tests. */
    public interface Clock {
        long nowMillis();
    }

    private final Source source;
    private final Clock clock;
    private final ConcurrentMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Object> locks = new ConcurrentHashMap<>();

    public EndpointCache(IonClient client) {
        this(client::endpoint, System::currentTimeMillis);
    }

    public EndpointCache(Source source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    /** A cached endpoint that is not due for refresh, or a freshly fetched one. */
    public AssetEndpoint get(long assetId) throws IonException {
        Entry entry = entries.get(assetId);
        if (entry != null && clock.nowMillis() < entry.refreshAtMillis) {
            return entry.endpoint;
        }
        Object lock = locks.computeIfAbsent(assetId, id -> new Object());
        synchronized (lock) {
            entry = entries.get(assetId);
            long now = clock.nowMillis();
            if (entry != null && now < entry.refreshAtMillis) {
                return entry.endpoint;
            }
            AssetEndpoint endpoint = source.fetch(assetId);
            entries.put(assetId, new Entry(endpoint, refreshAt(endpoint, now)));
            return endpoint;
        }
    }

    /**
     * Drops {@code staleEndpoint} after its credentials were rejected (HTTP
     * 401/403 from the tile host). Does nothing when the cache already holds
     * a newer endpoint, so a burst of failing tile requests causes one refetch.
     */
    public void invalidate(long assetId, AssetEndpoint staleEndpoint) {
        Entry entry = entries.get(assetId);
        if (entry != null && entry.endpoint == staleEndpoint) {
            entries.remove(assetId, entry);
        }
    }

    public void invalidate(long assetId) {
        entries.remove(assetId);
    }

    public void clear() {
        entries.clear();
    }

    static long refreshAt(AssetEndpoint endpoint, long fetchedAtMillis) {
        long ttl;
        if (endpoint.expiresAtEpochMillis() > 0) {
            long lifetime = endpoint.expiresAtEpochMillis() - fetchedAtMillis;
            ttl = Math.max(lifetime - REFRESH_MARGIN_MS, lifetime / 2);
        } else {
            ttl = DEFAULT_TTL_MS;
        }
        return fetchedAtMillis + Math.max(ttl, MIN_TTL_MS);
    }

    private static final class Entry {
        final AssetEndpoint endpoint;
        final long refreshAtMillis;

        Entry(AssetEndpoint endpoint, long refreshAtMillis) {
            this.endpoint = endpoint;
            this.refreshAtMillis = refreshAtMillis;
        }
    }
}
