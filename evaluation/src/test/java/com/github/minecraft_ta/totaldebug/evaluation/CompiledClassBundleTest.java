package com.github.minecraft_ta.totaldebug.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompiledClassBundleTest {
    @Test
    void preservesTheExistingClassBundleFormat() throws Exception {
        String encoded = CompiledClassBundle.encode(Map.of("X", new byte[]{0x7f}));
        assertEquals(base64("00000001000158000000017f"), encoded);
        assertArrayEquals(new byte[]{0x7f}, CompiledClassBundle.decode(encoded).get("X"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "00000000", "00000101", "0000000100015800000000",
            "0000000100015801000001", "0000000100015800000002ff",
            "00000002000158000000017f000158000000017f",
            "00000001000158000000017f00"
    })
    void rejectsInvalidCountsSizesDuplicateNamesTruncationAndTrailingBytes(String hex) {
        assertThrows(IOException.class, () -> CompiledClassBundle.decode(base64(hex)));
    }

    @Test
    void encoderRejectsDefinitionsTheTargetCannotAccept() {
        assertThrows(IOException.class, () -> CompiledClassBundle.encode(Map.of()));
        assertThrows(IOException.class, () -> CompiledClassBundle.encode(Map.of("X", new byte[0])));
    }

    private static String base64(String hex) {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
    }
}
