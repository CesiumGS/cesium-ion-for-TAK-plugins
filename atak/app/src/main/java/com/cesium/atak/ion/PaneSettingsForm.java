package com.cesium.atak.ion;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.cesium.tak.ion.IonException;

import java.security.GeneralSecurityException;
import java.util.function.Consumer;

/**
 * Connection settings inside the Cesium ion pane. They use the same
 * preference keys and {@link SecureTokenStore} as Tool Preferences, so both
 * stay in sync, and they work even where ATAK's Tool Preferences registry is
 * unavailable to plugins.
 */
final class PaneSettingsForm {
    private final Context context;
    private final PaneStyle style;
    private final SharedPreferences preferences;
    private final SecureTokenStore tokens;
    private final Consumer<String> log;
    private final Runnable onSaved;

    private LinearLayout root;
    private RadioButton cloud;
    private RadioButton selfHosted;
    private LinearLayout selfHostedFields;
    private EditText serverUrl;
    private EditText apiUrl;
    private CheckBox singleUser;
    private CheckBox allowHttp;
    private EditText token;
    private TextView tokenState;
    private View removeToken;

    PaneSettingsForm(Context context, PaneStyle style, SharedPreferences preferences, SecureTokenStore tokens,
            Consumer<String> log, Runnable onSaved) {
        this.context = context;
        this.style = style;
        this.preferences = preferences;
        this.tokens = tokens;
        this.log = log;
        this.onSaved = onSaved;
    }

    View view() {
        if (root == null) {
            build();
        }
        load();
        return root;
    }

    private void build() {
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, style.dp(12), 0, 0);
        root.addView(style.divider());
        TextView server = style.medium("Server", 14, PaneStyle.TEXT);
        server.setPadding(0, style.dp(12), 0, 0);
        root.addView(server);

        RadioGroup mode = new RadioGroup(context);
        mode.setOrientation(RadioGroup.HORIZONTAL);
        cloud = radio("Cesium ion Cloud");
        selfHosted = radio("Self-Hosted");
        mode.addView(cloud);
        mode.addView(selfHosted);
        mode.setOnCheckedChangeListener((group, checkedId) -> updateVisibility());
        root.addView(mode);

        selfHostedFields = new LinearLayout(context);
        selfHostedFields.setOrientation(LinearLayout.VERTICAL);
        serverUrl = field("Server URL, e.g. https://ion.example.mil", InputType.TYPE_TEXT_VARIATION_URI);
        apiUrl = field("API URL (optional; discovered from config.json)", InputType.TYPE_TEXT_VARIATION_URI);
        singleUser = check("Single-user server (no token)");
        singleUser.setOnCheckedChangeListener((button, checked) -> updateVisibility());
        allowHttp = check("Allow unencrypted HTTP (isolated lab networks only)");
        selfHostedFields.addView(spaced(serverUrl));
        selfHostedFields.addView(spaced(apiUrl));
        selfHostedFields.addView(singleUser);
        selfHostedFields.addView(allowHttp);
        root.addView(selfHostedFields);

        TextView access = style.medium(IonSignIn.isAvailable() ? "Or paste an access token" : "Access token", 14,
                PaneStyle.TEXT);
        access.setPadding(0, style.dp(12), 0, 0);
        root.addView(access);
        token = field("Paste a token with assets:list and assets:read", InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(spaced(token));
        tokenState = style.text("", 12, PaneStyle.MUTED);
        tokenState.setPadding(0, style.dp(6), 0, 0);
        root.addView(tokenState);

        LinearLayout buttons = style.horizontal();
        buttons.setPadding(0, style.dp(10), 0, 0);
        buttons.addView(style.button("Save", PaneStyle.ButtonKind.PRIMARY, v -> save()));
        removeToken = style.button("Remove saved token", PaneStyle.ButtonKind.DANGER, v -> removeToken());
        buttons.addView(removeToken, PaneStyle.leftMargin(style.dp(8)));
        root.addView(buttons);
    }

    private void load() {
        IonSettings settings = IonSettings.from(preferences.getAll());
        cloud.setChecked(settings.mode == IonSettings.Mode.CLOUD);
        selfHosted.setChecked(settings.mode == IonSettings.Mode.SELF_HOSTED);
        serverUrl.setText(settings.serverUrl);
        apiUrl.setText(settings.apiUrl);
        singleUser.setChecked(settings.singleUser);
        allowHttp.setChecked(settings.allowHttp);
        token.setText("");
        updateVisibility();
    }

    private IonSettings formSettings() {
        return new IonSettings(
                selfHosted.isChecked() ? IonSettings.Mode.SELF_HOSTED : IonSettings.Mode.CLOUD,
                serverUrl.getText().toString(),
                apiUrl.getText().toString(),
                allowHttp.isChecked(),
                singleUser.isChecked());
    }

    private void save() {
        IonSettings settings = formSettings();
        try {
            settings.server();
        } catch (IonException invalid) {
            log.accept("Settings not saved: " + invalid.getMessage());
            return;
        }
        preferences.edit()
                .putString(IonSettings.KEY_MODE, settings.mode.id)
                .putString(IonSettings.KEY_SERVER_URL, settings.serverUrl)
                .putString(IonSettings.KEY_API_URL, settings.apiUrl)
                .putBoolean(IonSettings.KEY_SINGLE_USER, settings.singleUser)
                .putBoolean(IonSettings.KEY_ALLOW_HTTP, settings.allowHttp)
                .apply();
        String typed = token.getText().toString().trim();
        token.setText("");
        if (!typed.isEmpty() && settings.needsToken()) {
            try {
                tokens.save(settings.tokenSlot(), typed);
                log.accept("Settings saved; access token stored encrypted for " + settings.describe() + ".");
            } catch (IllegalArgumentException invalid) {
                log.accept("Settings saved, but that does not look like an access token (spaces or line breaks?).");
            } catch (GeneralSecurityException failure) {
                PluginLog.e("Could not encrypt the Cesium ion token", failure);
                log.accept("Settings saved, but the token could not be stored securely on this device.");
            }
        } else {
            log.accept("Settings saved: " + settings.describe() + ".");
        }
        updateVisibility();
        onSaved.run();
    }

    private void removeToken() {
        IonSettings settings = formSettings();
        tokens.clear(settings.tokenSlot());
        log.accept("Saved access token removed for " + settings.describe() + ".");
        updateVisibility();
        onSaved.run();
    }

    private void updateVisibility() {
        boolean isSelfHosted = selfHosted.isChecked();
        selfHostedFields.setVisibility(isSelfHosted ? View.VISIBLE : View.GONE);
        IonSettings settings = formSettings();
        boolean needsToken = settings.needsToken();
        token.setVisibility(needsToken ? View.VISIBLE : View.GONE);
        removeToken.setVisibility(needsToken && tokens.has(settings.tokenSlot()) ? View.VISIBLE : View.GONE);
        if (!needsToken) {
            tokenState.setText("No token is used with a single-user server.");
        } else if (tokens.has(settings.tokenSlot())) {
            tokenState.setText("A token is saved (hidden). Type a new one to replace it.");
        } else {
            tokenState.setText("No token saved for " + (isSelfHosted ? "Self-Hosted" : "Cesium ion Cloud") + ".");
        }
    }

    private RadioButton radio(String label) {
        RadioButton radio = new RadioButton(context);
        radio.setId(View.generateViewId());
        radio.setText(label);
        radio.setTextColor(PaneStyle.TEXT);
        radio.setMinHeight(style.dp(44));
        return radio;
    }

    private CheckBox check(String label) {
        CheckBox box = new CheckBox(context);
        box.setText(label);
        box.setTextColor(PaneStyle.TEXT);
        box.setMinHeight(style.dp(44));
        return box;
    }

    private EditText field(String hint, int variation) {
        return style.field(hint, variation);
    }

    private View spaced(View field) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = style.dp(8);
        field.setLayoutParams(params);
        return field;
    }
}
