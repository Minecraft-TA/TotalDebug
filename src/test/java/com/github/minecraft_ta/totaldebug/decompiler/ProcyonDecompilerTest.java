package com.github.minecraft_ta.totaldebug.decompiler;

import com.github.minecraft_ta.totaldebug.bytecode.ClassLoaderBytecodeSource;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcyonDecompilerTest {
    @Test
    void decompilesAJava21Class() {
        String source = ProcyonDecompiler.decompile(
                Fixture.class,
                ClassLoaderBytecodeSource.forClass(Fixture.class)
        );

        assertTrue(source.contains("class Fixture"), source);
        assertTrue(source.contains("return 42"), source);
    }

    @Test
    void decompilesTheMinecraftBlockClass() {
        String source = ProcyonDecompiler.decompile(
                Block.class,
                ClassLoaderBytecodeSource.forClass(Block.class)
        );

        assertTrue(source.contains("class Block"), source);
        assertTrue(source.contains("defaultBlockState"), source);
    }

    private static final class Fixture {
        int answer() {
            return 42;
        }
    }
}
