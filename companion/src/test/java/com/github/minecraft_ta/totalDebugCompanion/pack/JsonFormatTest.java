package com.github.minecraft_ta.totalDebugCompanion.pack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFormatTest {
    @Test
    void valuesAreLaidOutOnePerLineAsWritten() {
        String minified = "{\"item.gear\":\"Gear <b>=</b> \\u00e9\",\"weight\":1.50,\"big\":1e3,\"list\":[1,2],\"empty\":{}}";
        assertEquals("""
                {
                  "item.gear": "Gear <b>=</b> é",
                  "weight": 1.50,
                  "big": 1e3,
                  "list": [
                    1,
                    2
                  ],
                  "empty": {}
                }""", JsonFormat.format(minified), "keys keep their order, numbers their spelling, and markup stays unescaped");
        assertTrue(JsonFormat.format("[1]\n").endsWith("]\n"), "a closing line break stays");
    }

    @Test
    void onlyStrictJsonIsLaidOut() {
        IllegalArgumentException comment = assertThrows(IllegalArgumentException.class,
                () -> JsonFormat.format("{// the gear\n\"a\":1}"));
        assertEquals("Malformed JSON at line 1 column 3", comment.getMessage(), "no advice to programmers");
        assertThrows(IllegalArgumentException.class, () -> JsonFormat.format("{\"a\":1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> JsonFormat.format("{\"a\":TRUE}"), "strict, not the legacy leniency");
        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class,
                () -> JsonFormat.format("{\"a\":{\"b\":1,\"b\":2}}"), "one of the two would be lost");
        assertEquals("\"b\" appears twice in one object at line 1 column 16", twice.getMessage());
        assertTrue(JsonFormat.formats("assets/ns/lang/en_us.json"));
        assertTrue(JsonFormat.formats("assets/ns/textures/block/gear.png.mcmeta"));
        assertFalse(JsonFormat.formats("data/ns/function/tick.mcfunction"));
    }
}
