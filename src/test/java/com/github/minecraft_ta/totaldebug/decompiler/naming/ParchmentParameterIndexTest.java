package com.github.minecraft_ta.totaldebug.decompiler.naming;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParchmentParameterIndexTest {
    private final ParchmentParameterIndex index = ParchmentParameterIndex.load();

    @Test
    void loadsPinnedParchmentParameters() {
        assertEquals(
                Map.of(1, "level", 2, "random", 3, "pos", 4, "state"),
                this.index.find(
                        "net/minecraft/world/level/block/BonemealableBlock",
                        "performBonemeal",
                        "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/util/RandomSource;"
                                + "Lnet/minecraft/core/BlockPos;"
                                + "Lnet/minecraft/world/level/block/state/BlockState;)V"
                )
        );
        assertEquals(
                Map.of(1, "reporter", 2, "lootData"),
                this.index.find(
                        "net/minecraft/advancements/Advancement",
                        "validate",
                        "(Lnet/minecraft/util/ProblemReporter;Lnet/minecraft/core/HolderGetter$Provider;)V"
                )
        );
    }

    @Test
    void reportsAnActuallyUnmappedMethod() {
        assertTrue(this.index.find(
                "net/minecraft/world/level/block/GrassBlock",
                "<init>",
                "(Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)V"
        ).isEmpty());
    }
}
