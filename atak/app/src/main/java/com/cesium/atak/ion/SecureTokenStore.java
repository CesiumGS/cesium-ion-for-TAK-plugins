package com.cesium.atak.ion;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.cesium.tak.ion.util.Secret;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Cesium ion access tokens, encrypted with an AES-256-GCM key held in the
 * Android Keystore. Only ciphertext reaches disk, in a preferences file
 * separate from ATAK's own (which ATAK can export).
 *
 * <p>Tokens are kept per {@link IonSettings#tokenSlot() slot} (Cloud vs
 * Self-Hosted), and each ciphertext is bound to its slot so it cannot be
 * moved to the other. Plugins share ATAK's process, so this protects tokens
 * at rest, not from other code running inside ATAK.
 */
final class SecureTokenStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "cesium-ion-token-key";
    private static final String PREFERENCES = "cesium_ion_secure";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private final SharedPreferences preferences;
    /** Held strongly: SharedPreferences keeps listeners in a weak map. */
    private SharedPreferences.OnSharedPreferenceChangeListener changeListener;

    SecureTokenStore(Context hostContext) {
        preferences = hostContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** Runs {@code listener} (on the main thread) when a token is saved or removed; {@code null} stops. */
    synchronized void setOnChangeListener(Runnable listener) {
        if (changeListener != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(changeListener);
        }
        changeListener = listener == null ? null : (changed, key) -> listener.run();
        if (changeListener != null) {
            preferences.registerOnSharedPreferenceChangeListener(changeListener);
        }
    }

    /**
     * Encrypts and saves {@code token} for {@code slot}.
     *
     * @throws IllegalArgumentException when the token is blank or malformed
     */
    synchronized void save(String slot, String token) throws GeneralSecurityException {
        Secret validated = Secret.of(token);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(slot.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(validated.reveal().getBytes(StandardCharsets.UTF_8));
        preferences.edit()
                .putString(slot + ".iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString(slot + ".ct", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .apply();
    }

    /**
     * The token for {@code slot}, or {@code null} when none is saved or it can
     * no longer be decrypted (for example after the Keystore key was reset), in
     * which case the stale ciphertext is removed.
     */
    synchronized Secret load(String slot) {
        String iv = preferences.getString(slot + ".iv", null);
        String ciphertext = preferences.getString(slot + ".ct", null);
        if (iv == null || ciphertext == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)));
            cipher.updateAAD(slot.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP));
            return Secret.of(new String(plaintext, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException unreadable) {
            PluginLog.w("Saved Cesium ion token for " + slot + " could not be decrypted; removing it.");
            clear(slot);
            return null;
        }
    }

    synchronized boolean has(String slot) {
        return preferences.contains(slot + ".ct");
    }

    synchronized void clear(String slot) {
        preferences.edit().remove(slot + ".iv").remove(slot + ".ct").apply();
    }

    private static SecretKey key() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            }
        } catch (java.io.IOException unavailable) {
            throw new GeneralSecurityException("Android Keystore is unavailable.", unavailable);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
