package com.cesium.atak.ion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

public class CreditWindowTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final CreditWindow window = new CreditWindow(now::get);

    @Test
    public void ordersCreditsByHowManyTilesCiteThem() {
        window.recordTile(glb("Google;Landsat"));
        window.recordTile(glb("Google;Airbus"));
        window.recordTile(glb("Airbus"));
        window.recordTile(new byte[] {1, 2, 3});
        assertEquals("Google; Airbus; Landsat", window.line());
    }

    @Test
    public void keepsThePreviousWindowUntilNewTilesArrive() {
        window.recordTile(glb("Google;Landsat"));
        now.addAndGet(CreditWindow.WINDOW_MILLIS);
        assertEquals("Google; Landsat", window.line());
        window.recordTile(glb("Google;Maxar"));
        assertEquals("Google; Maxar", window.line());
    }

    @Test
    public void keepsTheLastCreditsWhileNothingStreams() {
        // A finished view streams no new tiles but still shows them: Google's
        // attribution must stay on screen.
        window.recordTile(glb("Google;Landsat"));
        assertEquals("Google; Landsat", window.line());
        now.addAndGet(10 * CreditWindow.WINDOW_MILLIS);
        assertEquals("Google; Landsat", window.line());
        window.recordTile(glb("Google;Maxar"));
        assertEquals("Google; Maxar", window.line());
    }

    @Test
    public void hasNoCreditsBeforeAnyTile() {
        now.addAndGet(3 * CreditWindow.WINDOW_MILLIS);
        assertEquals("", window.line());
    }

    /** A minimal GLB whose glTF asset carries {@code copyright}. */
    static byte[] glb(String copyright) {
        byte[] json = ("{\"asset\":{\"version\":\"2.0\",\"copyright\":\"" + copyright + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
        int padded = (json.length + 3) & ~3;
        ByteBuffer out = ByteBuffer.allocate(12 + 8 + padded).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(2).putInt(12 + 8 + padded);
        out.putInt(padded).putInt(0x4E4F534A).put(json);
        while (out.position() < out.capacity()) {
            out.put((byte) ' ');
        }
        return out.array();
    }
}
