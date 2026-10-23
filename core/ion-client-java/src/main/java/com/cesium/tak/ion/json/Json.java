package com.cesium.tak.ion.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal RFC 8259 JSON reader.
 *
 * <p>The core deliberately avoids JSON libraries: inside ATAK a plugin's copy
 * of Gson or org.json can resolve to ATAK's own (different) version, and
 * Android's built-in org.json differs from the desktop artifact.
 *
 * <p>Values map to: {@link Map} (insertion ordered), {@link List},
 * {@link String}, {@link Long} (integers that fit), {@link Double} (all other
 * numbers), {@link Boolean}, and {@code null}.
 */
public final class Json {
    /** Guards against stack exhaustion from hostile, deeply nested input. */
    public static final int MAX_DEPTH = 256;

    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    /** Parses any JSON value. */
    public static Object parse(String text) throws JsonException {
        if (text == null) {
            throw new JsonException("No JSON content.");
        }
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.position != text.length()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    /** Parses JSON whose top-level value must be an object. */
    public static JsonObject parseObject(String text) throws JsonException {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("Expected a JSON object.");
        }
        return JsonObject.wrap(value);
    }

    private Object readValue(int depth) throws JsonException {
        if (depth > MAX_DEPTH) {
            throw error("JSON nesting is too deep");
        }
        if (position >= text.length()) {
            throw error("Unexpected end of JSON");
        }
        char c = text.charAt(position);
        switch (c) {
            case '{':
                return readObject(depth);
            case '[':
                return readArray(depth);
            case '"':
                return readString();
            case 't':
                expectLiteral("true");
                return Boolean.TRUE;
            case 'f':
                expectLiteral("false");
                return Boolean.FALSE;
            case 'n':
                expectLiteral("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw error("Unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> readObject(int depth) throws JsonException {
        Map<String, Object> result = new LinkedHashMap<>();
        position++; // '{'
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return result;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("Expected an object key");
            }
            String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw error("Expected ':' after object key");
            }
            position++;
            skipWhitespace();
            result.put(key, readValue(depth + 1));
            skipWhitespace();
            char next = peek();
            position++;
            if (next == '}') {
                return result;
            }
            if (next != ',') {
                position--;
                throw error("Expected ',' or '}' in object");
            }
        }
    }

    private List<Object> readArray(int depth) throws JsonException {
        List<Object> result = new ArrayList<>();
        position++; // '['
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return result;
        }
        while (true) {
            skipWhitespace();
            result.add(readValue(depth + 1));
            skipWhitespace();
            char next = peek();
            position++;
            if (next == ']') {
                return result;
            }
            if (next != ',') {
                position--;
                throw error("Expected ',' or ']' in array");
            }
        }
    }

    private String readString() throws JsonException {
        StringBuilder decoded = new StringBuilder();
        position = readStringLiteral(text, position, decoded);
        return decoded.toString();
    }

    /**
     * Decodes the JSON string literal whose opening quote is at
     * {@code quoteIndex}, appending its value to {@code decoded}.
     *
     * @return the index just past the closing quote
     */
    public static int readStringLiteral(String text, int quoteIndex, StringBuilder decoded)
            throws JsonException {
        if (quoteIndex >= text.length() || text.charAt(quoteIndex) != '"') {
            throw new JsonException("Expected a string at offset " + quoteIndex + ".");
        }
        int index = quoteIndex + 1;
        int runStart = index;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '"') {
                decoded.append(text, runStart, index);
                return index + 1;
            }
            if (c < 0x20) {
                throw new JsonException("Unescaped control character in string at offset " + index + ".");
            }
            if (c == '\\') {
                decoded.append(text, runStart, index);
                index = readEscape(text, index + 1, decoded);
                runStart = index;
                continue;
            }
            index++;
        }
        throw new JsonException("Unterminated string starting at offset " + quoteIndex + ".");
    }

    private static int readEscape(String text, int index, StringBuilder decoded) throws JsonException {
        if (index >= text.length()) {
            throw new JsonException("Unterminated escape sequence at offset " + index + ".");
        }
        char c = text.charAt(index++);
        switch (c) {
            case '"':
                decoded.append('"');
                return index;
            case '\\':
                decoded.append('\\');
                return index;
            case '/':
                decoded.append('/');
                return index;
            case 'b':
                decoded.append('\b');
                return index;
            case 'f':
                decoded.append('\f');
                return index;
            case 'n':
                decoded.append('\n');
                return index;
            case 'r':
                decoded.append('\r');
                return index;
            case 't':
                decoded.append('\t');
                return index;
            case 'u':
                if (index + 4 > text.length()) {
                    throw new JsonException("Truncated unicode escape at offset " + index + ".");
                }
                int codeUnit = 0;
                for (int i = 0; i < 4; i++) {
                    int digit = Character.digit(text.charAt(index++), 16);
                    if (digit < 0) {
                        throw new JsonException("Invalid unicode escape at offset " + index + ".");
                    }
                    codeUnit = (codeUnit << 4) | digit;
                }
                // Surrogate pairs arrive as two consecutive escapes; appending
                // each UTF-16 code unit reproduces the pair.
                decoded.append((char) codeUnit);
                return index;
            default:
                throw new JsonException("Invalid escape sequence '\\" + c + "' at offset " + index + ".");
        }
    }

    private Object readNumber() throws JsonException {
        int start = position;
        boolean integral = true;
        if (peek() == '-') {
            position++;
        }
        if (peek() == '0') {
            position++;
        } else if (isDigit(peek())) {
            while (isDigit(peek())) {
                position++;
            }
        } else {
            throw error("Invalid number");
        }
        if (peek() == '.') {
            integral = false;
            position++;
            if (!isDigit(peek())) {
                throw error("Invalid number fraction");
            }
            while (isDigit(peek())) {
                position++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            integral = false;
            position++;
            if (peek() == '+' || peek() == '-') {
                position++;
            }
            if (!isDigit(peek())) {
                throw error("Invalid number exponent");
            }
            while (isDigit(peek())) {
                position++;
            }
        }
        String literal = text.substring(start, position);
        if (integral) {
            try {
                return Long.parseLong(literal);
            } catch (NumberFormatException overflow) {
                // Falls through to a double for integers beyond the long range.
            }
        }
        return Double.parseDouble(literal);
    }

    private void expectLiteral(String literal) throws JsonException {
        if (!text.startsWith(literal, position)) {
            throw error("Invalid literal");
        }
        position += literal.length();
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            position++;
        }
    }

    private char peek() {
        return position < text.length() ? text.charAt(position) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private JsonException error(String message) {
        return new JsonException(message + " at offset " + position + ".");
    }

    /** Appends {@code value} to {@code out} as a quoted JSON string. */
    public static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
