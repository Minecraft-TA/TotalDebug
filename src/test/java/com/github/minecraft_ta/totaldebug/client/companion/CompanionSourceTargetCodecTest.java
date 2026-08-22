package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.client.decompile.SourceTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionSourceTargetCodecTest {
    @Test
    void encodesTheExistingCompanionJdtValues() {
        assertEquals(
                new CompanionSourceTargetCodec.WireTarget(-1, ""),
                CompanionSourceTargetCodec.encode(SourceTarget.wholeClass())
        );
        assertEquals(
                new CompanionSourceTargetCodec.WireTarget(9, "run()V"),
                CompanionSourceTargetCodec.encode(SourceTarget.method("run()V"))
        );
        assertEquals(
                new CompanionSourceTargetCodec.WireTarget(8, "value"),
                CompanionSourceTargetCodec.encode(SourceTarget.field("value"))
        );
    }

    @Test
    void decodesCompanionTargetsWithoutLeakingJdtValues() {
        assertEquals(SourceTarget.wholeClass(), CompanionSourceTargetCodec.decode(-1, ""));
        assertEquals(SourceTarget.method("run()V"), CompanionSourceTargetCodec.decode(9, "run()V"));
        assertEquals(SourceTarget.field("value"), CompanionSourceTargetCodec.decode(8, "value"));
    }

    @Test
    void rejectsUnknownOrIncompleteCompanionTargets() {
        assertThrows(IllegalArgumentException.class, () -> CompanionSourceTargetCodec.decode(7, "Type"));
        assertThrows(IllegalArgumentException.class, () -> CompanionSourceTargetCodec.decode(9, ""));
        assertInstanceOf(SourceTarget.WholeClass.class, CompanionSourceTargetCodec.decode(-1, "ignored"));
    }
}
