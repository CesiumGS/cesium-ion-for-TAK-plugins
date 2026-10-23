package com.cesium.tak.ion.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A credit that must be displayed while an asset is visible.
 *
 * <p>Ion supplies these as HTML. Google Photorealistic 3D Tiles additionally
 * require per-tile copyright strings; see
 * {@link com.cesium.tak.ion.tiles.AttributionCollector}.
 */
public final class Attribution {
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern IMG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC = Pattern.compile("\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALT = Pattern.compile("\\balt\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);

    private final String html;
    private final boolean collapsible;

    public Attribution(String html, boolean collapsible) {
        this.html = html == null ? "" : html;
        this.collapsible = collapsible;
    }

    public String html() {
        return html;
    }

    /** Whether ion allows hiding this credit behind an expandable control. */
    public boolean isCollapsible() {
        return collapsible;
    }

    /** The credit without markup, for platforms that cannot render HTML. */
    public String plainText() {
        String text = TAG.matcher(html).replaceAll(" ");
        text = text.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&copy;", "©")
                .replace("&amp;", "&");
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    /** An image the credit shows (a logo), with its alt text. */
    public static final class Image {
        public final String source;
        public final String alt;

        Image(String source, String alt) {
            this.source = source;
            this.alt = alt;
        }
    }

    /**
     * The credit's images whose source is an https URL on {@code trustedHost}
     * (ion serves the Google and Cesium logos from assets.ion.cesium.com).
     * Other images are ignored: a platform downloads only what it trusts.
     */
    public List<Image> images(String trustedHost) {
        List<Image> images = new ArrayList<>();
        Matcher tags = IMG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            Matcher src = SRC.matcher(tag);
            if (!src.find()) {
                continue;
            }
            String source = src.group(1).replace("&amp;", "&").trim();
            if (!source.startsWith("https://" + trustedHost + "/")) {
                continue;
            }
            Matcher alt = ALT.matcher(tag);
            images.add(new Image(source, alt.find() ? alt.group(1) : ""));
        }
        return Collections.unmodifiableList(images);
    }

    @Override
    public String toString() {
        return "Attribution[" + plainText() + "]";
    }
}
