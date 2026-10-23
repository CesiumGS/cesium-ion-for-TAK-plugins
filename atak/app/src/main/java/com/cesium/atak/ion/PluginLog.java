package com.cesium.atak.ion;

import android.util.Log;

import com.cesium.tak.ion.util.Redactor;

/** Logcat output under tag {@code CesiumIon}, always redacted. */
final class PluginLog {
    static final String TAG = "CesiumIon";

    private PluginLog() {
    }

    static void i(String message) {
        Log.i(TAG, Redactor.redact(message));
    }

    static void w(String message) {
        Log.w(TAG, Redactor.redact(message));
    }

    static void e(String message, Throwable failure) {
        // Only the exception type and a redacted message: stack traces can
        // carry URLs with credentials in their messages.
        Log.e(TAG, Redactor.redact(message + ": " + failure.getClass().getName() + ": " + failure.getMessage()));
    }
}
