package com.github.minecraft_ta.totalDebugCompanion.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinsTest {
    private static final String LEVEL = "net.minecraft.world.level.Level";

    @TempDir Path directory;

    @Test
    void aModsMixinsAreReadFromItsConfigurationsAndClasses() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\n[[mods]]\nmodId=\"gears\"\n"
                + "[[mixins]] # the common ones\nconfig='gears.mixins.json'\n[[mixins]]\nconfig = \"gears.client.mixins.json\"\n")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("gears.mixins.json", "{\"package\":\"com.gears.mixin\",\"mixins\":[\"LevelMixin\",\"Missing\",\"Broken\"]}"
                .getBytes(StandardCharsets.UTF_8));
        entries.put("gears.client.mixins.json", "{\"package\":\"com.gears.mixin\",\"mixinPriority\":1200,\"client\":[\"ScreenAccessor\"]}"
                .getBytes(StandardCharsets.UTF_8));
        entries.put("com/gears/mixin/LevelMixin.class", MixinFixtures.mixinClass("com/gears/mixin/LevelMixin", LEVEL, 900,
                List.of(new MixinFixtures.Method("onTick", "Lorg/spongepowered/asm/mixin/injection/Inject;", "method", "tick()V"),
                        new MixinFixtures.Method("redirectExplode", "Lorg/spongepowered/asm/mixin/injection/Redirect;", "method",
                                "Lnet/minecraft/world/level/Level;explode(DDD)V"),
                        new MixinFixtures.Method("wrapTick", "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "method", "tick"))));
        entries.put("com/gears/mixin/Broken.class", new byte[]{1, 2, 3});
        entries.put("com/gears/mixin/ScreenAccessor.class", MixinFixtures.mixinClass("com/gears/mixin/ScreenAccessor",
                "net.minecraft.client.gui.screens.Screen", 1000,
                List.of(new MixinFixtures.Method("getWidth", "Lorg/spongepowered/asm/mixin/gen/Accessor;", null, null),
                        new MixinFixtures.Method("newScreen", "Lorg/spongepowered/asm/mixin/gen/Invoker;", null, null))));
        Path jar = jar("gears.jar", entries);

        Mixins.Read read = Mixins.read(jar.toUri(), List.of("gears"));
        List<Mixins.Mixin> mixins = read.mixins();
        assertEquals(2, mixins.size(), "a listed class the file does not hold is left out, and one that cannot be read");
        assertEquals(1, read.problems().size());
        assertTrue(read.problems().getFirst().startsWith("gears.jar: com.gears.mixin.Broken could not be read"), read.problems().getFirst());
        Mixins.Mixin level = mixins.getFirst();
        assertEquals("gears", level.modId());
        assertEquals("gears.mixins.json", level.config(), "a literal string after a commented table header");
        assertEquals(List.of(LEVEL), level.targets());
        assertEquals(Mixins.Side.BOTH, level.side());
        assertEquals(900, level.priority());
        assertEquals(List.of(new Mixins.Change("Inject", "tick", "()V", ""), new Mixins.Change("Redirect", "explode", "(DDD)V", LEVEL),
                new Mixins.Change("WrapOperation", "tick")), level.changes(), "each with the overload its descriptor picks");
        Mixins.Mixin accessor = mixins.get(1);
        assertEquals(Mixins.Side.CLIENT, accessor.side());
        assertEquals(1200, accessor.priority(), "without its own priority, the configuration's");
        assertEquals(List.of(new Mixins.Change("Accessor", "width"), new Mixins.Change("Invoker", "<init>")), accessor.changes(),
                "named after the methods getWidth and newScreen, which makes one");
    }

    @Test
    void anInjectorCanNameItsTargetWithADescriptor() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "com/gears/mixin/DescMixin", null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType("net/minecraft/world/level/Level"));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "onTick", "()V", null, null);
        AnnotationVisitor inject = method.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        AnnotationVisitor described = inject.visitArray("target");
        AnnotationVisitor desc = described.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/Desc;");
        desc.visit("value", "tickChunk");
        desc.visitEnd();
        described.visitEnd();
        inject.visitEnd();
        method.visitEnd();
        writer.visitEnd();

        Mixins.Mixin read = Mixins.mixin("gears", "gears.mixins.json", "com.gears.mixin.DescMixin", Mixins.Side.BOTH, 1000,
                writer.toByteArray()).orElseThrow();
        assertEquals(List.of(new Mixins.Change("Inject", "tickChunk")), read.changes(), "not a mixin that only adds members");
    }

    @Test
    void theNameOfAMemberDropsItsOwnerAndDescriptor() {
        assertEquals("tick", Mixins.member("tick"));
        assertEquals("tick", Mixins.member("tick()V"));
        assertEquals("tick", Mixins.member("Lnet/minecraft/world/level/Level;tick()V"));
        assertEquals(LEVEL, Mixins.owner("Lnet/minecraft/world/level/Level;tick()V"));
        assertEquals("", Mixins.owner("tick()V"));
        assertEquals("(I)V", Mixins.descriptor("Lnet/minecraft/world/level/Level;tick(I)V"));
        assertEquals("", Mixins.descriptor("tick"));
        assertEquals("<init>", Mixins.member("<init>(Ljava/lang/String;)V"));
        assertEquals("get*", Mixins.member("get*"), "a wildcard names several members, not the class");
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
