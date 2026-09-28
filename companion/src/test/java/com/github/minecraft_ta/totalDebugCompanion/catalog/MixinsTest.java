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
        entries.put("gears.mixins.json", "{\"package\":\"com.gears.mixin\",\"mixins\":[\"LevelMixin\",\"Missing\",\"Broken\",\"Huge\"]}"
                .getBytes(StandardCharsets.UTF_8));
        entries.put("gears.client.mixins.json", "{\"package\":\"com.gears.mixin\",\"mixinPriority\":1200,\"client\":[\"ScreenAccessor\"]}"
                .getBytes(StandardCharsets.UTF_8));
        entries.put("com/gears/mixin/LevelMixin.class", MixinFixtures.mixinClass("com/gears/mixin/LevelMixin", LEVEL, 900,
                List.of(new MixinFixtures.Method("onTick", "Lorg/spongepowered/asm/mixin/injection/Inject;", "method", "tick()V"),
                        new MixinFixtures.Method("redirectExplode", "Lorg/spongepowered/asm/mixin/injection/Redirect;", "method",
                                "Lnet/minecraft/world/level/Level;explode(DDD)V"),
                        new MixinFixtures.Method("wrapTick", "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "method", "tick"))));
        entries.put("com/gears/mixin/Broken.class", new byte[]{1, 2, 3});
        entries.put("com/gears/mixin/Huge.class", new byte[4 * 1024 * 1024 + 1]);
        entries.put("com/gears/mixin/ScreenAccessor.class", MixinFixtures.mixinClass("com/gears/mixin/ScreenAccessor",
                "net.minecraft.client.gui.screens.Screen", 1000,
                List.of(new MixinFixtures.Method("getWidth", "Lorg/spongepowered/asm/mixin/gen/Accessor;", null, null, "()I"),
                        new MixinFixtures.Method("newScreen", "Lorg/spongepowered/asm/mixin/gen/Invoker;", null, null,
                                "(Ljava/lang/String;)Lnet/minecraft/client/gui/screens/Screen;"))));
        Path jar = jar("gears.jar", entries);

        Mixins.Read read = Mixins.read(jar.toUri(), List.of("gears"));
        List<Mixins.Mixin> mixins = read.mixins();
        assertEquals(2, mixins.size(), "a listed class the file does not hold is left out, and one that cannot be read");
        assertEquals(2, read.problems().size());
        assertTrue(read.problems().getFirst().startsWith("gears.jar: com.gears.mixin.Broken could not be read"), read.problems().getFirst());
        assertTrue(read.problems().get(1).contains("com/gears/mixin/Huge.class is larger than 4 MiB"), read.problems().get(1));
        Mixins.Mixin level = mixins.getFirst();
        assertEquals("gears", level.modId());
        assertEquals("gears.mixins.json", level.config(), "a literal string after a commented table header");
        assertEquals(List.of(LEVEL), level.targets());
        assertEquals(Mixins.Side.BOTH, level.side());
        assertEquals(900, level.priority());
        assertEquals(List.of(new Mixins.Change("Inject", new MixinSelector.Method("tick", "()V")),
                new Mixins.Change("Redirect", new MixinSelector.Method("explode", "(DDD)V"), LEVEL),
                new Mixins.Change("WrapOperation", new MixinSelector.Method("tick", ""))), level.changes(),
                "each with the overload its descriptor picks, or the first of the name");
        Mixins.Mixin accessor = mixins.get(1);
        assertEquals(Mixins.Side.CLIENT, accessor.side());
        assertEquals(1200, accessor.priority(), "without its own priority, the configuration's");
        assertEquals(List.of(new Mixins.Change("Accessor", new MixinSelector.Field("width")),
                new Mixins.Change("Invoker", new MixinSelector.Method("<init>", "(Ljava/lang/String;)V"))), accessor.changes(),
                "named after the methods getWidth, a field, and newScreen, the constructor it calls");
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
        AnnotationVisitor arguments = desc.visitArray("args");
        arguments.visit(null, Type.INT_TYPE);
        arguments.visitEnd();
        desc.visitEnd();
        described.visitEnd();
        inject.visitEnd();
        method.visitEnd();
        writer.visitEnd();

        Mixins.Mixin read = Mixins.mixin("gears", "gears.mixins.json", "com.gears.mixin.DescMixin", Mixins.Side.BOTH, 1000,
                writer.toByteArray()).orElseThrow();
        assertEquals(List.of(new Mixins.Change("Inject", new MixinSelector.Method("tickChunk", "(I)V"))), read.changes(),
                "the overload its arguments pick; not a mixin that only adds members");
    }

    @Test
    void aTargetIsReadAsItsClassFileDeclaresIt() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/world/level/Level", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "speed", "I", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "tick", "()V", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT, "create", "()V", null, null).visitEnd();
        writer.visitEnd();

        MixinTarget target = MixinTarget.read(writer.toByteArray());
        assertEquals("net/minecraft/world/level/Level", target.internalName());
        assertEquals(List.of("speed"), target.fields());
        assertEquals(List.of(new MixinTarget.Method("tick", "()V", false), new MixinTarget.Method("create", "()V", true)), target.methods(),
                "in the order the class declares them");
    }

    @Test
    void aModFileThatIsGoneMayDeclareMixins() {
        assertTrue(Mixins.declared(new CatalogIndex(CatalogFixtures.catalog(this.directory.resolve("gone.jar")))),
                "the Mixins page is where the missing file is named");
    }

    @Test
    void selectorsAreReadAsMixinReadsThem() {
        assertEquals(selected("tick", "", 1, ""), Mixins.selector("tick"), "without a quantifier, the first match");
        assertEquals(selected("tick", "()V", 1, ""), Mixins.selector(" tick ()V "), "whitespace is dropped");
        assertEquals(selected("tick", "(I)V", 1, LEVEL), Mixins.selector("Lnet/minecraft/world/level/Level;tick(I)V"));
        assertEquals(selected("tick", "", 1, LEVEL), Mixins.selector("net.minecraft.world.level.Level.tick"));
        assertEquals(selected("<init>", "(Ljava/lang/String;)V", 1, ""), Mixins.selector("<init>(Ljava/lang/String;)V"));
        assertEquals(selected("tick", "(I)V", 2, ""), Mixins.selector("tick{2}(I)V"), "a quantifier limits the matches");
        assertEquals(selected("tick", "", 3, ""), Mixins.selector("tick{1,3}"));
        assertEquals(selected("tick", "", Integer.MAX_VALUE, ""), Mixins.selector("tick{1,}"));
        assertEquals(selected("tick", "", 0, ""), Mixins.selector("tick{1"), "a malformed quantifier selects nothing");
        assertEquals(selected("render", "", Integer.MAX_VALUE, ""), Mixins.selector("render*"), "every method named render, not a prefix");
        assertEquals(selected("render", "", Integer.MAX_VALUE, ""), Mixins.selector("render+"));
        assertEquals(selected("", "", Integer.MAX_VALUE, ""), Mixins.selector("*"), "every method");
        assertEquals(new Mixins.Selected(new MixinSelector.Matching("", "^render", ""), ""), Mixins.selector("/^render/"));
        assertEquals(new Mixins.Selected(new MixinSelector.Matching("Level$", "^on", "V$"), ""),
                Mixins.selector("owner=/Level$/ name=/^on/ desc=/V$/"));
        assertEquals(new Mixins.Selected(new MixinSelector.Dynamic("@Shadow(tick)"), ""), Mixins.selector("@Shadow(tick)"));
        assertEquals("gears.mixins.json", Mixins.basicString("gears\\u002Emixins.json"), "a TOML escape");
        assertEquals("a\"b", Mixins.basicString("a\\\"b"));
    }

    private static Mixins.Selected selected(String name, String descriptor, int limit, String owner) {
        return new Mixins.Selected(new MixinSelector.Method(name, descriptor, limit), owner);
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
