package com.github.minecraft_ta.totaldebug.protocol.execution;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScriptBytecodeTest {
    @Test
    void roundTripsPrimaryAndNestedClasses() {
        var expected = new ScriptBytecode("Example", Map.of(
                "Example", new byte[]{1, 2}, "Example$Nested", new byte[]{3, 4}));
        var output = new ByteBufferOutputStream();
        expected.write(output);
        var actual = ScriptBytecode.read(input(output));
        assertEquals(expected.primaryClass(), actual.primaryClass());
        assertEquals(expected.classes().keySet(), actual.classes().keySet());
        for (String name : expected.classes().keySet()) assertArrayEquals(expected.classes().get(name), actual.classes().get(name));
    }

    @Test
    void rejectsMissingPrimaryClassAndOversizeOutput() {
        assertThrows(IllegalArgumentException.class, () -> new ScriptBytecode("Missing", Map.of("Other", new byte[]{1})));
        assertThrows(IllegalArgumentException.class, () -> new ScriptBytecode("Big", Map.of("Big", new byte[ScriptBytecode.MAX_BYTES])));
    }

    @Test
    void rejectsDuplicateAndTruncatedClassDefinitions() {
        var duplicate = new ByteBufferOutputStream();
        duplicate.writeString("Example");
        duplicate.writeInt(2);
        for (int i = 0; i < 2; i++) {
            duplicate.writeString("Example");
            duplicate.writeInt(1);
            duplicate.writeByte((byte) 1);
        }
        assertThrows(IllegalArgumentException.class, () -> ScriptBytecode.read(input(duplicate)));
        var truncated = new ByteBufferOutputStream();
        truncated.writeString("Example");
        truncated.writeInt(1);
        truncated.writeString("Example");
        truncated.writeInt(10);
        assertThrows(IllegalArgumentException.class, () -> ScriptBytecode.read(input(truncated)));
    }

    private static ByteBufferInputStream input(ByteBufferOutputStream output) {
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        return new ByteBufferInputStream(buffer);
    }
}
