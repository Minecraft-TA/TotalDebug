package com.github.minecraft_ta.totalDebugCompanion.naming;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MethodParameterNamesTest {
    @Test void reservesRealNamesBeforeGeneratingFallbacks() {
        assertArrayEquals(new String[]{"j", "i", "s", "s1", "properties", "short1", "名前"},
                MethodParameterNames.resolve(new MethodParameterNames.Method("example/Test", "test",
                        "(IILjava/lang/String;Ljava/lang/String;Lfoo/Block$Properties;SLjava/lang/String;)V", 0,
                        new String[]{"arg0", "i", "p_23_", null, null, "class", "名前"}), null));
    }

    @Test void mappedNamesUseJvmSlotsAndExactOverloads() {
        assertArrayEquals(new int[]{1, 3, 5}, MethodParameterNames.slots("(JDI)V", 0));
        assertArrayEquals(new int[]{0, 2, 4}, MethodParameterNames.slots("(JDI)V", Opcodes.ACC_STATIC));
        assertArrayEquals(new String[]{"theCrash"}, MethodParameterNames.resolve(new MethodParameterNames.Method(
                "net/minecraft/client/Minecraft", "fillReport", "(Lnet/minecraft/CrashReport;)Lnet/minecraft/CrashReport;",
                0, new String[]{"arg0"}), null));
        assertArrayEquals(new String[]{"minecraft", "languageManager", "launchVersion", "options", "report"},
                MethodParameterNames.resolve(new MethodParameterNames.Method("net/minecraft/client/Minecraft", "fillReport",
                        "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/resources/language/LanguageManager;"
                                + "Ljava/lang/String;Lnet/minecraft/client/Options;Lnet/minecraft/CrashReport;)V",
                        Opcodes.ACC_STATIC, null), null));
    }

    @Test void inheritsMappingsOnlyForInstanceOverrides() {
        var hierarchy = new MethodParameterNames.Hierarchy() {
            @Override public List<String> parents(String owner) {
                return owner.endsWith("Child") ? List.of("net/minecraft/world/level/block/BonemealableBlock") : List.of();
            }
            @Override public Integer access(String owner, String name, String descriptor) { return Opcodes.ACC_PUBLIC; }
        };
        String descriptor = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/util/RandomSource;"
                + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V";
        assertArrayEquals(new String[]{"level", "random", "pos", "state"}, MethodParameterNames.resolve(
                new MethodParameterNames.Method("net/minecraft/test/Child", "performBonemeal", descriptor, 0, null), hierarchy));
        assertArrayEquals(new String[]{"serverlevel", "randomsource", "blockpos", "blockstate"}, MethodParameterNames.resolve(
                new MethodParameterNames.Method("net/minecraft/test/Child", "performBonemeal", descriptor, Opcodes.ACC_STATIC, null), hierarchy));
    }
}
