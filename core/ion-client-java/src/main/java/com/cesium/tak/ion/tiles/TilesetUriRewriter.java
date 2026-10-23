package com.cesium.tak.ion.tiles;

import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rewrites the content and subtree references in a 3D Tiles tileset JSON
 * document, leaving every other byte untouched.
 *
 * <p>A local tile proxy needs this for Google Photorealistic 3D Tiles: their
 * child references are absolute paths ({@code /v1/3dtiles/datasets/...}),
 * which a renderer would otherwise resolve against the proxy's root instead
 * of the per-asset route. Rewriting at the token level, rather than parsing
 * and re-serializing, keeps the 64-bit transforms and bounding volumes
 * byte-identical.
 *
 * <p>Values of the keys {@code uri} (3D Tiles 1.0/1.1 content, contents, and
 * implicit-tiling subtrees), {@code url} (pre-1.0 content), and
 * {@code schemaUri} (1.1 metadata) are passed to the mapper. Implicit-tiling
 * templates such as {@code {level}/{x}/{y}} are passed through as-is.
 *
 * <p>Optionally, {@code geometricError} values above a ceiling are lowered to
 * it. Google's root uses {@code 1e+100} as an "always refine" sentinel, which
 * ATAK's renderer does not traverse past. Clamping to a ceiling keeps every
 * parent's error at or above its children's, as 3D Tiles requires.
 */
public final class TilesetUriRewriter {
    public static final Set<String> REFERENCE_KEYS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("uri", "url", "schemaUri")));
    static final String GEOMETRIC_ERROR = "geometricError";

    /** Maps one reference; returning {@code null} keeps the original. */
    public interface ReferenceMapper {
        String map(String reference);
    }

    private TilesetUriRewriter() {
    }

    /** The rewritten document and the original references, in document order. */
    public static final class Result {
        private final String json;
        private final List<String> references;
        private final int rewrittenCount;
        private final int clampedCount;
        private final double maxGeometricError;

        Result(String json, List<String> references, int rewrittenCount, int clampedCount, double maxGeometricError) {
            this.json = json;
            this.references = Collections.unmodifiableList(references);
            this.rewrittenCount = rewrittenCount;
            this.clampedCount = clampedCount;
            this.maxGeometricError = maxGeometricError;
        }

        public String json() {
            return json;
        }

        public List<String> references() {
            return references;
        }

        public int rewrittenCount() {
            return rewrittenCount;
        }

        /** How many {@code geometricError} values were lowered to the ceiling. */
        public int clampedCount() {
            return clampedCount;
        }

        /** The largest {@code geometricError} in the input, or {@code NaN} when there is none. */
        public double maxGeometricError() {
            return maxGeometricError;
        }
    }

    /** Lists the references without changing anything. */
    public static List<String> references(String json) throws JsonException {
        return rewrite(json, reference -> null).references();
    }

    public static Result rewrite(String json, ReferenceMapper mapper) throws JsonException {
        return rewrite(json, mapper, Double.POSITIVE_INFINITY);
    }

    /**
     * As {@link #rewrite(String, ReferenceMapper)}, also lowering every
     * {@code geometricError} greater than {@code geometricErrorCeiling} to it.
     */
    public static Result rewrite(String json, ReferenceMapper mapper, double geometricErrorCeiling)
            throws JsonException {
        if (!(geometricErrorCeiling > 0)) {
            throw new IllegalArgumentException("The geometricError ceiling must be positive.");
        }
        int clamped = 0;
        double maxSeen = Double.NaN;
        StringBuilder out = new StringBuilder(json.length() + 256);
        List<String> references = new ArrayList<>();
        int rewritten = 0;
        // Each frame is an open object or array; for objects, the last key read
        // and whether the next string token is a key.
        Deque<Frame> stack = new ArrayDeque<>();
        int index = 0;
        int length = json.length();
        while (index < length) {
            char c = json.charAt(index);
            Frame top = stack.peek();
            switch (c) {
                case '{':
                    stack.push(new Frame(true));
                    if (stack.size() > Json.MAX_DEPTH) {
                        throw new JsonException("Tileset JSON nesting is too deep.");
                    }
                    out.append(c);
                    index++;
                    break;
                case '[':
                    stack.push(new Frame(false));
                    if (stack.size() > Json.MAX_DEPTH) {
                        throw new JsonException("Tileset JSON nesting is too deep.");
                    }
                    out.append(c);
                    index++;
                    break;
                case '}':
                case ']':
                    if (top == null || top.isObject != (c == '}')) {
                        throw new JsonException("Mismatched '" + c + "' at offset " + index + ".");
                    }
                    stack.pop();
                    out.append(c);
                    index++;
                    break;
                case ',':
                    if (top != null && top.isObject) {
                        top.expectingKey = true;
                    }
                    out.append(c);
                    index++;
                    break;
                case ':':
                    if (top != null && top.isObject) {
                        top.expectingKey = false;
                    }
                    out.append(c);
                    index++;
                    break;
                case '"': {
                    StringBuilder decoded = new StringBuilder();
                    int end = Json.readStringLiteral(json, index, decoded);
                    if (top != null && top.isObject && top.expectingKey) {
                        top.lastKey = decoded.toString();
                        out.append(json, index, end);
                    } else if (top != null && top.isObject && REFERENCE_KEYS.contains(top.lastKey)) {
                        String reference = decoded.toString();
                        references.add(reference);
                        String mapped = mapper.map(reference);
                        if (mapped == null || mapped.equals(reference)) {
                            out.append(json, index, end);
                        } else {
                            Json.writeString(out, mapped);
                            rewritten++;
                        }
                    } else {
                        out.append(json, index, end);
                    }
                    index = end;
                    break;
                }
                default:
                    if (top != null && top.isObject && !top.expectingKey && GEOMETRIC_ERROR.equals(top.lastKey)
                            && (c == '-' || (c >= '0' && c <= '9'))) {
                        int end = index + 1;
                        while (end < length && isNumberChar(json.charAt(end))) {
                            end++;
                        }
                        double value;
                        try {
                            value = Double.parseDouble(json.substring(index, end));
                        } catch (NumberFormatException malformed) {
                            throw new JsonException("Invalid geometricError at offset " + index + ".");
                        }
                        maxSeen = Double.isNaN(maxSeen) ? value : Math.max(maxSeen, value);
                        if (value > geometricErrorCeiling) {
                            out.append(formatNumber(geometricErrorCeiling));
                            clamped++;
                        } else {
                            out.append(json, index, end);
                        }
                        index = end;
                        break;
                    }
                    out.append(c);
                    index++;
            }
        }
        if (!stack.isEmpty()) {
            throw new JsonException("Tileset JSON ended before all objects and arrays were closed.");
        }
        return new Result(out.toString(), references, rewritten, clamped, maxSeen);
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
    }

    static String formatNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static final class Frame {
        final boolean isObject;
        boolean expectingKey;
        String lastKey;

        Frame(boolean isObject) {
            this.isObject = isObject;
            this.expectingKey = isObject;
        }
    }
}
