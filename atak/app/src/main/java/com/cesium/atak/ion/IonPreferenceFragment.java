package com.cesium.atak.ion;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.widget.Toast;

import com.atakmap.android.preference.PluginPreferenceFragment;

import java.security.GeneralSecurityException;

/**
 * Settings > Tool Preferences > Cesium ion.
 *
 * <p>Server settings persist as ordinary ATAK preferences. The access token
 * field is not persistent: its value is intercepted here, encrypted into
 * {@link SecureTokenStore}, and never written to ATAK's preferences, so it is
 * not included in ATAK preference exports.
 */
public class IonPreferenceFragment extends PluginPreferenceFragment {
    static final String KEY_TOKEN_ENTRY = "cesium_ion_token_entry";
    static final String KEY_TOKEN_CLEAR = "cesium_ion_token_clear";

    // Android recreates fragments through the no-arg constructor, so the
    // plugin context and token store must be reachable statically (the ATAK
    // plugin template uses the same pattern).
    private static Context staticPluginContext;
    private static SecureTokenStore staticTokenStore;

    public IonPreferenceFragment() {
        super(staticPluginContext, R.xml.ion_preferences);
    }

    @SuppressLint("ValidFragment")
    IonPreferenceFragment(Context pluginContext, SecureTokenStore tokenStore) {
        super(pluginContext, R.xml.ion_preferences);
        staticPluginContext = pluginContext;
        staticTokenStore = tokenStore;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ListPreference mode = (ListPreference) findPreference(IonSettings.KEY_MODE);
        EditTextPreference tokenEntry = (EditTextPreference) findPreference(KEY_TOKEN_ENTRY);
        Preference tokenClear = findPreference(KEY_TOKEN_CLEAR);
        if (mode == null || tokenEntry == null || tokenClear == null || staticTokenStore == null) {
            PluginLog.w("Cesium ion preferences are incomplete; token entry is disabled.");
            return;
        }

        mode.setSummary(mode.getEntry());
        mode.setOnPreferenceChangeListener((preference, value) -> {
            int index = mode.findIndexOfValue((String) value);
            mode.setSummary(index >= 0 ? mode.getEntries()[index] : null);
            updateTokenSummary(tokenEntry, IonSettings.Mode.fromId(value).id);
            return true;
        });

        tokenEntry.setOnPreferenceClickListener(preference -> {
            // Never pre-fill the dialog with a saved token.
            tokenEntry.getEditText().setText("");
            return false;
        });
        tokenEntry.setOnPreferenceChangeListener((preference, value) -> {
            String slot = currentSlot();
            String token = value == null ? "" : value.toString();
            try {
                staticTokenStore.save(slot, token);
                toast("Cesium ion token saved.");
            } catch (IllegalArgumentException invalid) {
                toast("That does not look like a Cesium ion token.");
            } catch (GeneralSecurityException failure) {
                PluginLog.e("Could not encrypt the Cesium ion token", failure);
                toast("Could not store the token securely on this device.");
            }
            updateTokenSummary(tokenEntry, slot);
            // Returning false keeps the value out of ATAK's preferences.
            return false;
        });
        tokenClear.setOnPreferenceClickListener(preference -> {
            String slot = currentSlot();
            staticTokenStore.clear(slot);
            updateTokenSummary(tokenEntry, slot);
            toast("Saved Cesium ion token removed.");
            return true;
        });

        updateTokenSummary(tokenEntry, currentSlot());
    }

    private String currentSlot() {
        return IonSettings.from(getPreferenceManager().getSharedPreferences().getAll()).tokenSlot();
    }

    private static void updateTokenSummary(EditTextPreference tokenEntry, String slot) {
        boolean cloud = IonSettings.Mode.CLOUD.id.equals(slot);
        String target = cloud ? "Cesium ion Cloud" : "Self-Hosted";
        tokenEntry.setSummary(staticTokenStore.has(slot)
                ? "Saved for " + target + " (hidden). Tap to replace."
                : "Not set for " + target + ".");
    }

    private void toast(String message) {
        Context context = getActivity();
        if (context != null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        }
    }
}
