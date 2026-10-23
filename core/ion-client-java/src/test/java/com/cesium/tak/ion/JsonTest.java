package com.cesium.tak.ion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonException;
import com.cesium.tak.ion.json.JsonObject;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {

    @Test
    void parsesNestedValues() throws Exception {
        JsonObject json = Json.parseObject(
                " {\"a\": [1, -2.5, 3e2, true, false, null], \"b\": {\"c\": \"d\"}, \"big\": 12345678901234567890} ");
        List<Object> a = json.optArray("a");
        assertEquals(Arrays.asList(1L, -2.5, 300.0, true, false, null), a);
        assertEquals("d", json.optObject("b").optString("c", null));
        assertEquals(1.2345678901234567e19, json.optDouble("big", 0), 1e4);
    }

    @Test
    void decodesEscapesAndSurrogatePairs() throws Exception {
        Object value = Json.parse("\"tab\\t quote\\\" slash\\/ snow\\u2603 pile\\ud83d\\udca9\"");
        assertEquals("tab\t quote\" slash/ snow\u2603 pile\ud83d\udca9", value);
    }

    @Test
    void keepsInsertionOrderAndLastDuplicateWins() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) Json.parse("{\"z\":1,\"a\":2,\"z\":3}");
        assertEquals(Arrays.asList("z", "a"), Arrays.asList(map.keySet().toArray()));
        assertEquals(3L, map.get("z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "{", "{\"a\"}", "{\"a\":}", "[1,]", "[1 2]", "01", "1.", "-", "1e", "tru", "nul",
        "\"unterminated", "\"bad \\x escape\"", "\"\\u12\"", "{\"a\":1} trailing", "\"control \u0001 char\""
    })
    void rejectsMalformedInput(String input) {
        assertThrows(JsonException.class, () -> Json.parse(input));
    }

    @Test
    void rejectsHostileNesting() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < Json.MAX_DEPTH + 10; i++) {
            deep.append('[');
        }
        assertThrows(JsonException.class, () -> Json.parse(deep.toString()));
    }

    @Test
    void typedAccessorsFallBackOnWrongTypes() throws Exception {
        JsonObject json = Json.parseObject("{\"s\":5,\"n\":\"5\",\"f\":2.0,\"g\":2.5,\"o\":[],\"blank\":\"  \"}");
        assertNull(json.optString("s", null));
        assertEquals(-1, json.optLong("n", -1));
        assertEquals(2, json.optLong("f", -1));
        assertEquals(-1, json.optLong("g", -1));
        assertEquals(true, json.optObject("o").isEmpty());
        assertNull(json.optNonBlank("blank"));
    }

    @Test
    void writeStringRoundTrips() throws Exception {
        String original = "line\nbreak \"quoted\" back\\slash \u0007 bell";
        StringBuilder out = new StringBuilder();
        Json.writeString(out, original);
        assertEquals(original, Json.parse(out.toString()));
    }
}
