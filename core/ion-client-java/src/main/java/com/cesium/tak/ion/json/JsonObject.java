package com.cesium.tak.ion.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Typed, lenient read access to a parsed JSON object.
 *
 * <p>Every {@code opt*} accessor returns the fallback when the key is missing
 * or holds a value of another type, because ion responses vary between cloud,
 * self-hosted, and older API versions.
 */
public final class JsonObject {
    private static final JsonObject EMPTY = new JsonObject(Collections.<String, Object>emptyMap());

    private final Map<String, Object> values;

    private JsonObject(Map<String, Object> values) {
        this.values = values;
    }

    /** Wraps a parsed value, or returns an empty object when it is not a JSON object. */
    @SuppressWarnings("unchecked")
    public static JsonObject wrap(Object value) {
        return value instanceof Map ? new JsonObject((Map<String, Object>) value) : EMPTY;
    }

    public boolean has(String key) {
        return values.containsKey(key) && values.get(key) != null;
    }

    public Object raw(String key) {
        return values.get(key);
    }

    public String optString(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    /** A string with surrounding whitespace removed, or {@code null} when missing or blank. */
    public String optNonBlank(String key) {
        String value = optString(key, null);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public long optLong(String key, long fallback) {
        Object value = values.get(key);
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Double) {
            double number = (Double) value;
            if (number == Math.rint(number) && Math.abs(number) < 9.0e15) {
                return (long) number;
            }
        }
        return fallback;
    }

    public double optDouble(String key, double fallback) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    public boolean optBoolean(String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    public JsonObject optObject(String key) {
        return wrap(values.get(key));
    }

    public boolean isObject(String key) {
        return values.get(key) instanceof Map;
    }

    @SuppressWarnings("unchecked")
    public List<Object> optArray(String key) {
        Object value = values.get(key);
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }

    /** Elements of an array that are JSON objects; other element types are skipped. */
    public List<JsonObject> optObjects(String key) {
        List<JsonObject> result = new ArrayList<>();
        for (Object element : optArray(key)) {
            if (element instanceof Map) {
                result.add(wrap(element));
            }
        }
        return result;
    }

    /** Elements of an array that are strings; other element types are skipped. */
    public List<String> optStrings(String key) {
        List<String> result = new ArrayList<>();
        for (Object element : optArray(key)) {
            if (element instanceof String) {
                result.add((String) element);
            }
        }
        return result;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
