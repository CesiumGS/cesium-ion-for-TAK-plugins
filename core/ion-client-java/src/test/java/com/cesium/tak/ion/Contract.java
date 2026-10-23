package com.cesium.tak.ion;

import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Loads the language-neutral cases in spec/ion-contract. */
final class Contract {
    private Contract() {
    }

    static Path root() {
        String dir = System.getProperty("ionContractDir");
        if (dir == null) {
            throw new IllegalStateException("Run tests through Gradle so ionContractDir is set.");
        }
        return Paths.get(dir);
    }

    static String text(String relativePath) {
        try {
            return new String(Files.readAllBytes(root().resolve(relativePath)), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Missing contract file " + relativePath, failure);
        }
    }

    static JsonObject load(String relativePath) {
        try {
            return Json.parseObject(text(relativePath));
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid contract file " + relativePath, failure);
        }
    }

    /** Re-serializes a parsed JSON value; used to hand fixture responses to fake transports. */
    static String toJson(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            Json.writeString(out, (String) value);
        } else if (value instanceof java.util.Map) {
            out.append('{');
            boolean first = true;
            for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) value).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                Json.writeString(out, (String) entry.getKey());
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof java.util.List) {
            out.append('[');
            boolean first = true;
            for (Object element : (java.util.List<?>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, element);
            }
            out.append(']');
        } else {
            out.append(value);
        }
    }
}
