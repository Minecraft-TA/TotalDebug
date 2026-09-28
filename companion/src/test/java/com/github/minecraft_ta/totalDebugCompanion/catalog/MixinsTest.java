package com.github.minecraft_ta.totalDebugCompanion.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MixinsTest {
    private static final String LEVEL = "net.minecraft.world.level.Level";

    @TempDir Path directory;

    @Test
    void aModsMixinsAreReadFromItsConfigurationsAndClasses() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\n[[mods]]\nmodId=\"gears\"\n"
                + "[[mixins]]\nconfig=\"gears.mixins.json\"\n[[mixins]]\nconfig = \"gears.client.mixins.json\"\n").getBytes(StandardCharsets.UTF_8));
        entries.put("gears.mixins.json", "{\"package\":\"com.gears.mixin\",\"mixins\":[\"LevelMixin\",\"Missing\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("gears.client.mixins.json", "{\"package\":\"com.gears.mixin\",\"client\":[\"ScreenAccessor\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("com/gears/mixin/LevelMixin.class", MixinFixtures.mixinClass("com/gears/mixin/LevelMixin", LEVEL, 900,
                List.of(new MixinFixtures.Method("onTick", "Lorg/spongepowered/asm/mixin/injection/Inject;", "method", "tick()V"),
                        new MixinFixtures.Method("redirectExplode", "Lorg/spongepowered/asm/mixin/injection/Redirect;", "method",
                                "Lnet/minecraft/world/level/Level;explode(DDD)V"),
                        new MixinFixtures.Method("wrapTick", "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "method", "tick"))));
        entries.put("com/gears/mixin/ScreenAccessor.class", MixinFixtures.mixinClass("com/gears/mixin/ScreenAccessor",
                "net.minecraft.client.gui.screens.Screen", 1000,
                List.of(new MixinFixtures.Method("getWidth", "Lorg/spongepowered/asm/mixin/gen/Accessor;", null, null))));
        Path jar = jar("gears.jar", entries);

        List<Mixins.Mixin> mixins = Mixins.read(jar.toUri(), List.of("gears"));
        assertEquals(2, mixins.size(), "a listed class the file does not hold is left out");
        Mixins.Mixin level = mixins.getFirst();
        assertEquals("gears", level.modId());
        assertEquals("gears.mixins.json", level.config());
        assertEquals(List.of(LEVEL), level.targets());
        assertEquals(Mixins.Side.BOTH, level.side());
        assertEquals(900, level.priority());
        assertEquals(List.of(new Mixins.Change("Inject", "tick"), new Mixins.Change("Redirect", "explode"),
                new Mixins.Change("WrapOperation", "tick")), level.changes());
        Mixins.Mixin accessor = mixins.get(1);
        assertEquals(Mixins.Side.CLIENT, accessor.side());
        assertEquals(List.of(new Mixins.Change("Accessor", "width")), accessor.changes(), "named after the method getWidth");
    }

    @Test
    void theNameOfAMemberDropsItsOwnerAndDescriptor() {
        assertEquals("tick", Mixins.member("tick"));
        assertEquals("tick", Mixins.member("tick()V"));
        assertEquals("tick", Mixins.member("Lnet/minecraft/world/level/Level;tick()V"));
        assertEquals("<init>", Mixins.member("<init>(Ljava/lang/String;)V"));
    }

    private Path jar(String name, Map<String, byte[]> entries) throws IOException {
        Path jar = this.directory.resolve(name);
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream output = new JarOutputStream(file)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }
}
