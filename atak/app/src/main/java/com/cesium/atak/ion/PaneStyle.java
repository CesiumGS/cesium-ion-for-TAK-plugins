package com.cesium.atak.ion;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cesium.tak.ion.model.AssetType;

/**
 * The Cesium ion pane's look, from Cesium's brand guide (cesium.com/press):
 * Cesium black as the ground, Cesium blue for actions, and Cesium green for
 * what is on the map. Views are drawn in code (the plugin has no layouts,
 * and ATAK's theme varies between builds). Sizes are in dp; touch targets
 * are at least 44 dp for gloved use.
 */
final class PaneStyle {
    /** Cesium black (brand guide). */
    static final int BACKGROUND = Color.rgb(14, 20, 34);
    /** Rows and fields, a step above the ground. */
    static final int CARD = Color.rgb(23, 32, 51);
    static final int BORDER = Color.rgb(39, 50, 74);
    static final int TEXT = Color.rgb(233, 238, 245);
    static final int MUTED = Color.rgb(142, 154, 176);
    /** Cesium blue (PMS 284): actions, and 3D Tiles. */
    static final int ACCENT = Color.rgb(109, 171, 228);
    static final int ON_ACCENT = BACKGROUND;
    /** Cesium green (PMS 567): on the map, connected, and terrain. */
    static final int GREEN = Color.rgb(112, 156, 73);
    /** Cesium green lightened for text on the dark ground. */
    static final int SUCCESS = Color.rgb(150, 194, 106);
    static final int WARNING = Color.rgb(227, 176, 75);
    static final int DANGER = Color.rgb(232, 120, 100);
    static final int IMAGERY = Color.rgb(90, 183, 164);
    static final int VECTOR = Color.rgb(167, 155, 230);

    /** PRIMARY is filled (one per view); ADD is outlined in Cesium blue, for lists of many. */
    enum ButtonKind { PRIMARY, ADD, SECONDARY, DANGER, TEXT }

    private final Context context;
    private final float density;

    PaneStyle(Context context) {
        this.context = context;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    int dp(float value) {
        return Math.round(value * density);
    }

    TextView text(String value, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sizeSp);
        return view;
    }

    TextView bold(String value, float sizeSp) {
        TextView view = text(value, sizeSp, TEXT);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    /** Roboto Medium, for names and headings. */
    TextView medium(String value, float sizeSp, int color) {
        TextView view = text(value, sizeSp, color);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    /** A section heading in sentence case, with an optional muted count. */
    TextView heading(String value) {
        TextView view = medium(value, 17, TEXT);
        view.setSingleLine(true);
        return view;
    }

    /**
     * A section: heading (and an action on the right), then its body, on the
     * pane's ground. Sections are separated by space and a rule, not boxes.
     */
    LinearLayout section(TextView heading, View action) {
        LinearLayout section = vertical();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(20);
        section.setLayoutParams(params);
        View rule = divider();
        section.addView(rule);
        LinearLayout header = horizontal();
        header.setPadding(0, dp(14), 0, dp(6));
        header.setMinimumHeight(dp(44));
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) {
            header.addView(action);
        }
        section.addView(header);
        return section;
    }

    /** A raised panel for grouped rows. */
    LinearLayout card() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(rounded(CARD, BORDER, 10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    /** A card header: a heading on the left, an optional action on the right. */
    LinearLayout cardHeader(String title, View action) {
        LinearLayout header = horizontal();
        header.addView(medium(title, 15, TEXT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) {
            header.addView(action);
        }
        return header;
    }

    LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    LinearLayout vertical() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    Button button(String label, ButtonKind kind, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setMinWidth(dp(64));
        button.setMinimumWidth(dp(64));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setStateListAnimator(null);
        button.setElevation(0);
        styleButton(button, kind);
        button.setOnClickListener(listener);
        return button;
    }

    void styleButton(Button button, ButtonKind kind) {
        int fill;
        int stroke;
        int textColor;
        switch (kind) {
            case PRIMARY:
                fill = ACCENT;
                stroke = ACCENT;
                textColor = ON_ACCENT;
                break;
            case ADD:
                fill = withAlpha(ACCENT, 26);
                stroke = withAlpha(ACCENT, 150);
                textColor = ACCENT;
                break;
            case DANGER:
                fill = Color.TRANSPARENT;
                stroke = withAlpha(DANGER, 130);
                textColor = DANGER;
                break;
            case TEXT:
                fill = Color.TRANSPARENT;
                stroke = Color.TRANSPARENT;
                textColor = ACCENT;
                break;
            default:
                fill = Color.TRANSPARENT;
                stroke = BORDER;
                textColor = TEXT;
        }
        button.setTextColor(new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[0]},
                new int[] {withAlpha(TEXT, 110), textColor}));
        // Disabled buttons drop their fill so they never look tappable.
        StateListDrawable shape = new StateListDrawable();
        shape.addState(new int[] {-android.R.attr.state_enabled},
                rounded(Color.TRANSPARENT, kind == ButtonKind.TEXT ? Color.TRANSPARENT : BORDER, 22));
        shape.addState(new int[0], rounded(fill, stroke, 22));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), shape, null));
    }

    /**
     * A 44 dp round icon button (gloved use) with a tooltip: shown on hover with a
     * mouse or stylus, and on long-press on touch screens. {@link View#setSelected}
     * shows it as active (e.g. while the panel it opens is open).
     */
    ImageButton iconButton(Drawable icon, String description, View.OnClickListener listener) {
        ImageButton button = new ImageButton(context);
        button.setImageDrawable(icon);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setMinimumWidth(dp(44));
        button.setMinimumHeight(dp(44));
        StateListDrawable shape = new StateListDrawable();
        shape.addState(new int[] {android.R.attr.state_selected}, rounded(withAlpha(ACCENT, 40), withAlpha(ACCENT, 150), 22));
        shape.addState(new int[0], rounded(Color.TRANSPARENT, BORDER, 22));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), shape, null));
        button.setOnClickListener(listener);
        return button;
    }

    /** A filter pill: filled when {@code selected}, outlined otherwise. */
    TextView chip(String label, boolean selected, View.OnClickListener listener) {
        TextView chip = medium(label, 13, selected ? ON_ACCENT : TEXT);
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(dp(36));
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setBackground(rounded(selected ? ACCENT : Color.TRANSPARENT, selected ? ACCENT : BORDER, 18));
        chip.setClickable(true);
        chip.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        chip.setLayoutParams(params);
        return chip;
    }

    /** The connection pill: a tinted capsule with a dot. */
    TextView statusPill(String label, int color) {
        TextView pill = medium("", 13, color);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(12), dp(6), dp(14), dp(6));
        setStatus(pill, label, color);
        return pill;
    }

    void setStatus(TextView pill, String label, int color) {
        pill.setText("●  " + label);
        pill.setTextColor(color);
        pill.setBackground(rounded(withAlpha(color, 34), withAlpha(color, 90), 16));
    }

    /** A small "On the map" tag in Cesium green. */
    TextView onMapTag() {
        TextView tag = medium("On the map", 11, SUCCESS);
        tag.setPadding(dp(8), dp(2), dp(8), dp(2));
        tag.setBackground(rounded(withAlpha(GREEN, 50), Color.TRANSPARENT, 10));
        return tag;
    }

    static int typeColor(AssetType type) {
        switch (type) {
            case TILES_3D:
                return ACCENT;
            case IMAGERY:
                return IMAGERY;
            case TERRAIN:
                return SUCCESS;
            case GEOJSON:
            case KML:
                return VECTOR;
            default:
                return MUTED;
        }
    }

    /** A 40 dp tile holding the type's glyph, tinted in the type's colour. */
    View typeIcon(AssetType type) {
        View icon = new View(context);
        int color = typeColor(type);
        icon.setBackground(new TypeGlyph(type, color, withAlpha(color, 38), dp(10)));
        icon.setContentDescription(type.ionName());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(40), dp(40));
        params.rightMargin = dp(12);
        icon.setLayoutParams(params);
        return icon;
    }

    /**
     * A list row: icon, name (with an optional tag) over up to two detail
     * lines, and an action on the right. Long names end in an ellipsis
     * rather than pushing the action off.
     */
    LinearLayout listRow(View icon, String name, View tag, String detail, String hint, View action) {
        LinearLayout row = horizontal();
        row.setPadding(0, dp(10), 0, dp(10));
        row.setMinimumHeight(dp(60));
        if (icon != null) {
            row.addView(icon);
        }
        LinearLayout labels = vertical();
        LinearLayout titleRow = horizontal();
        TextView title = medium(name, 15, TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (tag != null) {
            titleRow.addView(tag, leftMargin(dp(8)));
            // The tag follows the name; a long name shrinks (weight) rather
            // than pushing the tag out, and the row wraps its content.
            title.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            labels.addView(titleRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            labels.addView(titleRow);
        }
        if (detail != null && !detail.isEmpty()) {
            TextView second = text(detail, 12, MUTED);
            second.setSingleLine(true);
            second.setEllipsize(TextUtils.TruncateAt.END);
            second.setPadding(0, dp(2), 0, 0);
            labels.addView(second);
        }
        if (hint != null && !hint.isEmpty()) {
            TextView third = text(hint, 12, TEXT);
            third.setPadding(0, dp(2), 0, 0);
            labels.addView(third);
        }
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.rightMargin = dp(10);
        row.addView(labels, labelParams);
        if (action != null) {
            row.addView(action);
        }
        return row;
    }

    View divider() {
        View line = new View(context);
        line.setBackgroundColor(BORDER);
        line.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        return line;
    }

    EditText field(String hint, int inputVariation) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | inputVariation);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setTextSize(14);
        field.setMinHeight(dp(44));
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackground(rounded(CARD, BORDER, 22));
        return field;
    }

    /** Vertical gap. */
    View space(int heightDp) {
        View space = new View(context);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return space;
    }

    static LinearLayout.LayoutParams leftMargin(int px) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = px;
        return params;
    }

    GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT) {
            shape.setStroke(Math.max(1, dp(1)), stroke);
        }
        return shape;
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** A settings gear, drawn on a 24-unit grid in {@code color}. */
    static final class GearGlyph extends Drawable {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        GearGlyph(int color) {
            line.setColor(color);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float unit = Math.min(bounds.width(), bounds.height()) / 24f;
            canvas.save();
            canvas.translate(bounds.centerX() - 12 * unit, bounds.centerY() - 12 * unit);
            canvas.scale(unit, unit);
            line.setStrokeWidth(1.8f);
            // Eight teeth around a ring, then the hub.
            Path gear = new Path();
            int teeth = 8;
            for (int i = 0; i < teeth * 4; i++) {
                double angle = Math.PI * 2 * i / (teeth * 4) - Math.PI / 2;
                float radius = (i % 4 == 1 || i % 4 == 2) ? 10f : 7.6f;
                float x = 12 + (float) (Math.cos(angle) * radius);
                float y = 12 + (float) (Math.sin(angle) * radius);
                if (i == 0) {
                    gear.moveTo(x, y);
                } else {
                    gear.lineTo(x, y);
                }
            }
            gear.close();
            canvas.drawPath(gear, line);
            canvas.drawCircle(12, 12, 3.2f, line);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            line.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            line.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return 72;
        }

        @Override
        public int getIntrinsicHeight() {
            return 72;
        }
    }

    /**
     * A rounded tile with a line glyph for the asset type: a cube (3D Tiles),
     * stacked sheets (imagery), peaks (terrain), or a polyline (vector).
     * Drawn on a 24-unit grid.
     */
    static final class TypeGlyph extends Drawable {
        private final AssetType type;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;

        TypeGlyph(AssetType type, int color, int background, float radius) {
            this.type = type;
            this.radius = radius;
            fill.setColor(background);
            line.setColor(color);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            dot.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.drawRoundRect(new RectF(bounds), radius, radius, fill);
            float size = Math.min(bounds.width(), bounds.height());
            float unit = size * 0.6f / 24f;
            canvas.save();
            canvas.translate(bounds.centerX() - 12 * unit, bounds.centerY() - 12 * unit);
            canvas.scale(unit, unit);
            line.setStrokeWidth(1.8f);
            Path path = new Path();
            switch (type) {
                case TILES_3D:
                    path.moveTo(12, 2.5f);
                    path.lineTo(21, 7.25f);
                    path.lineTo(21, 16.75f);
                    path.lineTo(12, 21.5f);
                    path.lineTo(3, 16.75f);
                    path.lineTo(3, 7.25f);
                    path.close();
                    path.moveTo(3, 7.25f);
                    path.lineTo(12, 12);
                    path.lineTo(21, 7.25f);
                    path.moveTo(12, 12);
                    path.lineTo(12, 21.5f);
                    canvas.drawPath(path, line);
                    break;
                case IMAGERY:
                    path.moveTo(12, 3);
                    path.lineTo(22, 8.5f);
                    path.lineTo(12, 14);
                    path.lineTo(2, 8.5f);
                    path.close();
                    path.moveTo(2, 13);
                    path.lineTo(12, 18.5f);
                    path.lineTo(22, 13);
                    path.moveTo(2, 17);
                    path.lineTo(12, 22.5f);
                    path.lineTo(22, 17);
                    canvas.drawPath(path, line);
                    break;
                case TERRAIN:
                    path.moveTo(1.5f, 20);
                    path.lineTo(8.5f, 9);
                    path.lineTo(12.5f, 15);
                    path.lineTo(15.5f, 11);
                    path.lineTo(22.5f, 20);
                    path.close();
                    canvas.drawPath(path, line);
                    canvas.drawCircle(17.5f, 5.5f, 2f, dot);
                    break;
                case GEOJSON:
                case KML:
                    path.moveTo(4, 18);
                    path.lineTo(9.5f, 8);
                    path.lineTo(15, 15);
                    path.lineTo(20, 5);
                    canvas.drawPath(path, line);
                    canvas.drawCircle(4, 18, 2.4f, dot);
                    canvas.drawCircle(9.5f, 8, 2.4f, dot);
                    canvas.drawCircle(15, 15, 2.4f, dot);
                    canvas.drawCircle(20, 5, 2.4f, dot);
                    break;
                default:
                    canvas.drawCircle(12, 12, 3, dot);
            }
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            line.setAlpha(alpha);
            dot.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            line.setColorFilter(colorFilter);
            dot.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
