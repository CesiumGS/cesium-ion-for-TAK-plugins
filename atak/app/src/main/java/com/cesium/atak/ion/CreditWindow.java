package com.cesium.atak.ion;

import com.cesium.tak.ion.tiles.AttributionCollector;

import java.util.function.LongSupplier;

/**
 * The credit line for one tileset's recently streamed tiles. ATAK cannot
 * report which tiles are on screen, so credits come from the tiles the
 * proxy served in the last window (Google requires the copyrights of the
 * visible tiles, most frequent first). When the view stops streaming new
 * tiles, what is on screen has not changed, so the last credits stay until
 * new tiles replace them. Thread-safe.
 */
final class CreditWindow {
    static final long WINDOW_MILLIS = 30_000;

    private final LongSupplier clock;
    private AttributionCollector current = new AttributionCollector();
    private AttributionCollector previous = new AttributionCollector();
    private long windowStart;
    private String lastLine = "";

    CreditWindow(LongSupplier clock) {
        this.clock = clock;
        this.windowStart = clock.getAsLong();
    }

    /** Records one tile's raw content; tiles without a glTF copyright are ignored. */
    synchronized void recordTile(byte[] content) {
        rotateIfDue();
        current.recordTile(content);
    }

    /**
     * Credits of the current window, or of the previous one while the
     * current window has none yet (just after a rotation); when neither has
     * any (the view stopped streaming new tiles), the last credits shown.
     */
    synchronized String line() {
        rotateIfDue();
        String line = current.creditLine();
        if (line.isEmpty()) {
            line = previous.creditLine();
        }
        if (!line.isEmpty()) {
            lastLine = line;
        }
        return lastLine;
    }

    private void rotateIfDue() {
        long now = clock.getAsLong();
        if (now - windowStart < WINDOW_MILLIS) {
            return;
        }
        // After a gap longer than two windows both are empty; line() then
        // keeps the last credits, since the view has not changed.
        previous = now - windowStart < 2 * WINDOW_MILLIS ? current : new AttributionCollector();
        current = new AttributionCollector();
        windowStart = now;
    }
}
