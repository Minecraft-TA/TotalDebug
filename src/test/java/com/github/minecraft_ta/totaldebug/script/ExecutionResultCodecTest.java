package com.github.minecraft_ta.totaldebug.script;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionResultCodecTest {
    @Test
    void boundsScriptLogsBeforeTransportEncoding() {
        ExecutionTextBuffer output = new ExecutionTextBuffer(5);
        output.append("abc");
        output.append("defgh");

        ExecutionText snapshot = output.snapshot();

        assertEquals("abcde", snapshot.text());
        assertEquals(8, snapshot.totalCharacters());
        assertTrue(snapshot.truncated());
    }

    @Test
    void retainsResultsBeyondTheFormerCharacterLimit() {
        String text = "value-".repeat(50_000);
        ExecutionResult result = ExecutionResult.completed("", ExecutionValueCapture.capture(text));

        ExecutionResultCodec.Encoded encoded = ExecutionResultCodec.encode(result);
        ExecutionResult decoded = ExecutionResultCodec.decode(encoded.json());

        assertTrue(encoded.utf8Bytes() > 250_000);
        assertEquals(text, decoded.value().value().text());
        assertFalse(decoded.value().value().truncated());
    }

    @Test
    void fitsTheActualByteBudgetAndReportsWhatWasRetained() {
        String logs = "🚀".repeat(8_000);
        String value = "v".repeat(20_000);
        ExecutionResult result = ExecutionResult.completed(logs, ExecutionValueCapture.capture(value));

        ExecutionResultCodec.Encoded encoded = ExecutionResultCodec.encode(result, 4_096);
        ExecutionResult decoded = ExecutionResultCodec.decode(encoded.json());

        assertTrue(encoded.utf8Bytes() <= 4_096);
        assertTrue(decoded.logs().truncated());
        assertFalse(decoded.logs().text().isEmpty());
        assertEquals(logs.length(), decoded.logs().totalCharacters());
        assertTrue(decoded.value().value().truncated());
        assertFalse(decoded.value().value().text().isEmpty());
        assertEquals(value.length(), decoded.value().value().totalCharacters());
        assertFalse(decoded.logs().text().endsWith("\uD83D"), "transport split a surrogate pair");
    }

    @Test
    void preservesStructuredMapKeys() {
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put(42, "number key");
        map.put("name", "string key");
        ExecutionResult result = ExecutionResult.completed("", ExecutionValueCapture.capture(map));

        ExecutionValue decoded = ExecutionResultCodec.decode(
                ExecutionResultCodec.encode(result).json()
        ).value();

        assertEquals(ExecutionValue.Kind.MAP, decoded.kind());
        assertEquals(ExecutionValue.Kind.NUMBER, decoded.children().get(0).key().kind());
        assertEquals("42", decoded.children().get(0).key().value().text());
        assertEquals(ExecutionValue.Kind.STRING, decoded.children().get(1).key().kind());
        assertEquals("name", decoded.children().get(1).key().value().text());
    }
}
