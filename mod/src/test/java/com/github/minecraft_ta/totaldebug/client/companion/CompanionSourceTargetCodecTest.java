package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.client.decompile.SourceTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
