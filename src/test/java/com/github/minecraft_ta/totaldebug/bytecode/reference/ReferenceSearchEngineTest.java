package com.github.minecraft_ta.totaldebug.bytecode.reference;

import net.neoforged.jarjar.nio.layzip.LayeredZipFileSystemProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceSearchEngineTest {
    private static final String TARGET = "fixture/Target";
    private static final String REFERENCES = "fixture/References";

    @TempDir
    Path temporaryDirectory;

    @Test
    void findsExactClassReferencesAndReturnsExactMethods() throws Exception {
        Path classes = writeClassDirectory(REFERENCES, referenceFixture(REFERENCES));

        ReferenceSearchResult result = new ReferenceSearchEngine(List.of(classes), 2)
                .search(ReferenceQuery.classReference("fixture.Target"));

        assertFalse(result.cancelled());
        assertEquals(1, result.scannedClassFiles());
        assertEquals(1, result.totalClassFiles());
        assertIterableEquals(List.of(
                ReferenceLocation.classDeclaration(REFERENCES.replace('/', '.')),
                ReferenceLocation.field(REFERENCES.replace('/', '.'), "targetField", "Lfixture/Target;"),
                location("annotationUse", "()V"),
                location("classLiteral", "()V"),
                location("descriptorUse", "(Lfixture/Target;)Lfixture/Target;"),
                location("exceptionUse", "()V"),
                location("fieldInt", "()V"),
                location("fieldString", "()V"),
                location("genericUse", "(Ljava/util/List;)V"),
                location("methodHandle", "()V"),
                location("methodInt", "()V"),
                location("methodNoArgs", "()V"),
                location("typeInstructions", "()V"),
                ReferenceLocation.recordComponent(
                        REFERENCES.replace('/', '.'),
                        "targetComponent",
                        "Lfixture/Target;"
                )
        ), result.locations());
    }

    @Test
    void methodSearchMatchesOwnerNameAndDescriptorIncludingMethodHandles() throws Exception {
        Path classes = writeClassDirectory(REFERENCES, referenceFixture(REFERENCES));
        ReferenceSearchEngine engine = new ReferenceSearchEngine(List.of(classes), 1);

        ReferenceSearchResult noArgs = engine.search(
                ReferenceQuery.methodReference("fixture.Target", "run", "()V")
        );
        ReferenceSearchResult integer = engine.search(
                ReferenceQuery.methodReference("fixture.Target", "run", "(I)V")
        );
        ReferenceSearchResult wrongOwner = engine.search(
                ReferenceQuery.methodReference("fixture.Other", "run", "()V")
        );

        assertIterableEquals(
                List.of(location("methodHandle", "()V"), location("methodNoArgs", "()V")),
                noArgs.locations()
        );
        assertIterableEquals(List.of(location("methodInt", "()V")), integer.locations());
        assertTrue(wrongOwner.locations().isEmpty());
    }

    @Test
    void fieldSearchRequiresTheExactDescriptor() throws Exception {
        Path classes = writeClassDirectory(REFERENCES, referenceFixture(REFERENCES));
        ReferenceSearchEngine engine = new ReferenceSearchEngine(List.of(classes), 1);

        ReferenceSearchResult integer = engine.search(
                ReferenceQuery.fieldReference("fixture.Target", "VALUE", "I")
        );
        ReferenceSearchResult string = engine.search(
                ReferenceQuery.fieldReference("fixture.Target", "VALUE", "Ljava/lang/String;")
        );

        assertIterableEquals(List.of(location("fieldInt", "()V")), integer.locations());
        assertIterableEquals(List.of(location("fieldString", "()V")), string.locations());
    }

    @Test
    void resolvesInheritedMembersWithoutClassLoadingAndStopsAtOverrides() throws Exception {
        Path classes = this.temporaryDirectory.resolve("hierarchy-classes");
        writeClass(classes, TARGET, memberOwnerFixture(TARGET, "java/lang/Object", true));
        writeClass(classes, "fixture/Sub", memberOwnerFixture("fixture/Sub", TARGET, false));
        writeClass(classes, "fixture/Override", memberOwnerFixture("fixture/Override", TARGET, true));
        writeClass(classes, "fixture/HierarchyReferences", hierarchyReferencesFixture());
        ReferenceSearchEngine engine = new ReferenceSearchEngine(List.of(classes), 2);

        ReferenceSearchResult methods = engine.search(
                ReferenceQuery.methodReference("fixture.Target", "run", "()V")
        );
        ReferenceSearchResult fields = engine.search(
                ReferenceQuery.fieldReference("fixture.Target", "VALUE", "I")
        );

        assertEquals(
                List.of(ReferenceLocation.method("fixture.HierarchyReferences", "inheritedMethod", "()V")),
                methods.locations()
        );
        assertEquals(
                List.of(ReferenceLocation.method("fixture.HierarchyReferences", "inheritedField", "()V")),
                fields.locations()
        );
    }

    @Test
    void resolvesInheritedInterfaceMethodsWithoutJdkClassMetadata() throws Exception {
        String target = "fixture/InterfaceTarget";
        Path classes = this.temporaryDirectory.resolve("interface-hierarchy-classes");
        writeClass(classes, target, interfaceFixture(target, null, true));
        writeClass(classes, "fixture/ChildInterface", interfaceFixture("fixture/ChildInterface", target, false));
        writeClass(
                classes,
                "fixture/OverrideInterface",
                interfaceFixture("fixture/OverrideInterface", target, true)
        );
        writeClass(
                classes,
                "fixture/InterfaceImpl",
                implementationFixture("fixture/InterfaceImpl", "fixture/ChildInterface")
        );
        writeClass(
                classes,
                "fixture/OverrideImpl",
                implementationFixture("fixture/OverrideImpl", "fixture/OverrideInterface")
        );
        writeClass(classes, "fixture/InterfaceReferences", interfaceReferencesFixture());

        ReferenceSearchResult result = new ReferenceSearchEngine(List.of(classes), 2).search(
                ReferenceQuery.methodReference("fixture.InterfaceTarget", "run", "()V")
        );

        assertEquals(
                List.of(ReferenceLocation.method("fixture.InterfaceReferences", "inheritedInterface", "()V")),
                result.locations()
        );
    }

    @Test
    void scansOrdinaryAndNeoForgeVirtualArchives() throws Exception {
        byte[] fixture = referenceFixture(REFERENCES);
        Path directJar = this.temporaryDirectory.resolve("direct.jar");
        writeJar(directJar, null, List.of(new JarContent(REFERENCES + ".class", fixture)));

        assertEquals(
                List.of(location("methodHandle", "()V"), location("methodNoArgs", "()V")),
                new ReferenceSearchEngine(List.of(directJar), 1)
                        .search(ReferenceQuery.methodReference("fixture.Target", "run", "()V"))
                        .locations()
        );

        byte[] nestedJar = Files.readAllBytes(directJar);
        Path outerJar = this.temporaryDirectory.resolve("outer.jar");
        writeJar(outerJar, null, List.of(new JarContent("META-INF/jarjar/source.jar", nestedJar)));

        var provider = new LayeredZipFileSystemProvider();
        try (FileSystem fileSystem = provider.newFileSystem(outerJar)) {
            Path virtualJar = fileSystem.getPath("/META-INF/jarjar/source.jar");
            ReferenceSearchResult result = new ReferenceSearchEngine(List.of(virtualJar), 1)
                    .search(ReferenceQuery.methodReference("fixture.Target", "run", "()V"));

            assertEquals(
                    List.of(location("methodHandle", "()V"), location("methodNoArgs", "()V")),
                    result.locations()
            );
            assertEquals(1, result.scannedClassFiles());
        }
    }

    @Test
    void usesTheRuntimeViewOfMultiReleaseArchives() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");

        Path archive = this.temporaryDirectory.resolve("multi-release.jar");
        writeJar(archive, manifest, List.of(
                new JarContent("fixture/Multi.class", referenceFixture("fixture/Multi")),
                new JarContent("META-INF/versions/21/fixture/Multi.class", emptyFixture("fixture/Multi"))
        ));

        ReferenceSearchResult result = new ReferenceSearchEngine(List.of(archive), 1)
                .search(ReferenceQuery.methodReference("fixture.Target", "run", "()V"));

        assertTrue(result.locations().isEmpty());
        assertEquals(1, result.scannedClassFiles());
    }

    @Test
    void reportsDeterministicProgressAndCooperativeCancellation() throws Exception {
        Path classes = this.temporaryDirectory.resolve("cancel-classes");
        for (int index = 0; index < 4; index++) {
            writeClass(classes, "fixture/Cancel" + index, referenceFixture("fixture/Cancel" + index));
        }
        List<ReferenceSearchProgress> progress = new ArrayList<>();
        AtomicBoolean cancelled = new AtomicBoolean();

        ReferenceSearchResult result = new ReferenceSearchEngine(List.of(classes), 1).search(
                ReferenceQuery.classReference("fixture.Target"),
                new ReferenceSearchMonitor() {
                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }

                    @Override
                    public void onProgress(ReferenceSearchProgress update) {
                        progress.add(update);
                        if (update.processedClassFiles() == 1) {
                            cancelled.set(true);
                        }
                    }
                }
        );

        assertTrue(result.cancelled());
        assertEquals(1, result.scannedClassFiles());
        assertEquals(4, result.totalClassFiles());
        assertEquals(List.of(
                new ReferenceSearchProgress(ReferenceSearchPhase.SCANNING_REFERENCES, 0, 4),
                new ReferenceSearchProgress(ReferenceSearchPhase.SCANNING_REFERENCES, 1, 4)
        ), progress);
    }

    @Test
    void canCancelWhileResolvingInheritedMemberOwners() throws Exception {
        Path classes = this.temporaryDirectory.resolve("owner-cancel-classes");
        for (int index = 0; index < 3; index++) {
            writeClass(classes, "fixture/OwnerCancel" + index, emptyFixture("fixture/OwnerCancel" + index));
        }
        List<ReferenceSearchProgress> progress = new ArrayList<>();
        AtomicBoolean cancelled = new AtomicBoolean();

        ReferenceSearchResult result = new ReferenceSearchEngine(List.of(classes), 1).search(
                ReferenceQuery.methodReference("fixture.Target", "run", "()V"),
                new ReferenceSearchMonitor() {
                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }

                    @Override
                    public void onProgress(ReferenceSearchProgress update) {
                        progress.add(update);
                        if (update.phase() == ReferenceSearchPhase.RESOLVING_MEMBER_OWNERS
                                && update.processedClassFiles() == 1) {
                            cancelled.set(true);
                        }
                    }
                }
        );

        assertTrue(result.cancelled());
        assertEquals(0, result.scannedClassFiles());
        assertEquals(3, result.totalClassFiles());
        assertEquals(List.of(
                new ReferenceSearchProgress(ReferenceSearchPhase.RESOLVING_MEMBER_OWNERS, 0, 3),
                new ReferenceSearchProgress(ReferenceSearchPhase.RESOLVING_MEMBER_OWNERS, 1, 3)
        ), progress);
    }

    @Test
    void identifiesTheExactMalformedClassSource() throws Exception {
        Path classes = this.temporaryDirectory.resolve("broken-classes");
        Path broken = classes.resolve("fixture/Broken.class");
        Files.createDirectories(broken.getParent());
        Files.write(broken, new byte[]{1, 2, 3});

        IOException failure = assertThrows(
                IOException.class,
                () -> new ReferenceSearchEngine(List.of(classes), 1)
                        .search(ReferenceQuery.classReference("fixture.Target"))
        );

        assertTrue(failure.getMessage().contains(broken.toUri().toString()), failure.getMessage());
        assertTrue(failure.getCause() != null);
    }

    @Test
    void rejectsAmbiguousOrMalformedQueries() {
        assertThrows(IllegalArgumentException.class, () -> ReferenceQuery.classReference("fixture/Target"));
        assertThrows(IllegalArgumentException.class, () -> ReferenceQuery.classReference("[Lfixture.Target;"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReferenceQuery.fieldReference("fixture.Target", "VALUE", "()V")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ReferenceQuery.methodReference("fixture.Target", "run", "I")
        );
    }

    private Path writeClassDirectory(String internalName, byte[] bytes) throws IOException {
        Path classes = this.temporaryDirectory.resolve("classes");
        writeClass(classes, internalName, bytes);
        return classes;
    }

    private static void writeClass(Path root, String internalName, byte[] bytes) throws IOException {
        Path output = root.resolve(internalName + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, bytes);
    }

    private static ReferenceLocation location(String methodName, String descriptor) {
        return ReferenceLocation.method(REFERENCES.replace('/', '.'), methodName, descriptor);
    }

    private static byte[] referenceFixture(String internalName) {
        ClassWriter writer = beginClass(internalName, new String[]{TARGET});
        writer.visitField(Opcodes.ACC_PRIVATE, "targetField", "Lfixture/Target;", null, null).visitEnd();
        writer.visitRecordComponent("targetComponent", "Lfixture/Target;", null).visitEnd();
        emptyMethod(writer, "descriptorUse", "(Lfixture/Target;)Lfixture/Target;", null, Opcodes.ARETURN);
        emptyMethod(
                writer,
                "genericUse",
                "(Ljava/util/List;)V",
                "(Ljava/util/List<Lfixture/Target;>;)V",
                Opcodes.RETURN
        );
        var annotationUse = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "annotationUse",
                "()V",
                null,
                null
        );
        annotationUse.visitAnnotation("Lfixture/Target;", true).visitEnd();
        annotationUse.visitCode();
        annotationUse.visitInsn(Opcodes.RETURN);
        annotationUse.visitMaxs(0, 0);
        annotationUse.visitEnd();
        emptyMethodWithExceptions(writer, "exceptionUse", new String[]{TARGET});

        var methodNoArgs = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "methodNoArgs", "()V", null, null);
        methodNoArgs.visitCode();
        methodNoArgs.visitInsn(Opcodes.ACONST_NULL);
        methodNoArgs.visitMethodInsn(Opcodes.INVOKEINTERFACE, TARGET, "run", "()V", true);
        methodNoArgs.visitInsn(Opcodes.ACONST_NULL);
        methodNoArgs.visitMethodInsn(Opcodes.INVOKEINTERFACE, TARGET, "run", "()V", true);
        methodNoArgs.visitInsn(Opcodes.RETURN);
        methodNoArgs.visitMaxs(1, 0);
        methodNoArgs.visitEnd();

        var methodInt = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "methodInt", "()V", null, null);
        methodInt.visitCode();
        methodInt.visitInsn(Opcodes.ACONST_NULL);
        methodInt.visitInsn(Opcodes.ICONST_0);
        methodInt.visitMethodInsn(Opcodes.INVOKEINTERFACE, TARGET, "run", "(I)V", true);
        methodInt.visitInsn(Opcodes.RETURN);
        methodInt.visitMaxs(2, 0);
        methodInt.visitEnd();

        fieldMethod(writer, "fieldInt", "I", Opcodes.POP);
        fieldMethod(writer, "fieldString", "Ljava/lang/String;", Opcodes.POP);

        var classLiteral = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "classLiteral", "()V", null, null);
        classLiteral.visitCode();
        classLiteral.visitLdcInsn(Type.getObjectType(TARGET));
        classLiteral.visitInsn(Opcodes.POP);
        classLiteral.visitInsn(Opcodes.RETURN);
        classLiteral.visitMaxs(1, 0);
        classLiteral.visitEnd();

        var typeInstructions = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "typeInstructions",
                "()V",
                null,
                null
        );
        typeInstructions.visitCode();
        typeInstructions.visitInsn(Opcodes.ACONST_NULL);
        typeInstructions.visitTypeInsn(Opcodes.CHECKCAST, TARGET);
        typeInstructions.visitInsn(Opcodes.POP);
        typeInstructions.visitInsn(Opcodes.ICONST_1);
        typeInstructions.visitTypeInsn(Opcodes.ANEWARRAY, TARGET);
        typeInstructions.visitInsn(Opcodes.POP);
        typeInstructions.visitInsn(Opcodes.ICONST_1);
        typeInstructions.visitInsn(Opcodes.ICONST_1);
        typeInstructions.visitMultiANewArrayInsn("[[Lfixture/Target;", 2);
        typeInstructions.visitInsn(Opcodes.POP);
        typeInstructions.visitInsn(Opcodes.RETURN);
        typeInstructions.visitMaxs(2, 0);
        typeInstructions.visitEnd();

        Handle bootstrap = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;",
                false
        );
        var methodHandle = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "methodHandle", "()V", null, null);
        methodHandle.visitCode();
        methodHandle.visitInsn(Opcodes.ACONST_NULL);
        methodHandle.visitInvokeDynamicInsn(
                "run",
                "(Lfixture/Target;)Ljava/lang/Runnable;",
                bootstrap,
                Type.getMethodType("()V"),
                new Handle(Opcodes.H_INVOKEINTERFACE, TARGET, "run", "()V", true),
                Type.getMethodType("()V")
        );
        methodHandle.visitInsn(Opcodes.POP);
        methodHandle.visitInsn(Opcodes.RETURN);
        methodHandle.visitMaxs(1, 0);
        methodHandle.visitEnd();

        var unrelated = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "unrelated", "()V", null, null);
        unrelated.visitCode();
        unrelated.visitInsn(Opcodes.ACONST_NULL);
        unrelated.visitMethodInsn(Opcodes.INVOKEINTERFACE, "fixture/TargetExtra", "run", "()V", true);
        unrelated.visitInsn(Opcodes.RETURN);
        unrelated.visitMaxs(1, 0);
        unrelated.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyFixture(String internalName) {
        ClassWriter writer = beginClass(internalName, null);
        emptyMethod(writer, "empty", "()V", null, Opcodes.RETURN);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] memberOwnerFixture(String internalName, String superName, boolean declaresMembers) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, superName, null);
        if (declaresMembers) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "VALUE", "I", null, null).visitEnd();
            emptyMethod(writer, "run", "()V", null, Opcodes.RETURN);
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] hierarchyReferencesFixture() {
        ClassWriter writer = beginClass("fixture/HierarchyReferences", null);
        memberCallMethod(writer, "inheritedMethod", "fixture/Sub");
        memberCallMethod(writer, "overriddenMethod", "fixture/Override");
        memberFieldMethod(writer, "inheritedField", "fixture/Sub");
        memberFieldMethod(writer, "overriddenField", "fixture/Override");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] interfaceFixture(String internalName, String parent, boolean declaresMethod) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                internalName,
                null,
                "java/lang/Object",
                parent == null ? null : new String[]{parent}
        );
        if (declaresMethod) {
            writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "run", "()V", null, null).visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] implementationFixture(String internalName, String interfaceName) {
        ClassWriter writer = beginClass(internalName, new String[]{interfaceName});
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] interfaceReferencesFixture() {
        ClassWriter writer = beginClass("fixture/InterfaceReferences", null);
        memberCallMethod(writer, "inheritedInterface", "fixture/InterfaceImpl");
        memberCallMethod(writer, "overriddenInterface", "fixture/OverrideImpl");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter beginClass(String internalName, String[] interfaces) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", interfaces);
        return writer;
    }

    private static void emptyMethod(
            ClassWriter writer,
            String name,
            String descriptor,
            String signature,
            int returnOpcode
    ) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, signature, null);
        method.visitCode();
        if (returnOpcode == Opcodes.ARETURN) {
            method.visitInsn(Opcodes.ACONST_NULL);
        }
        method.visitInsn(returnOpcode);
        method.visitMaxs(returnOpcode == Opcodes.ARETURN ? 1 : 0, Type.getArgumentTypes(descriptor).length);
        method.visitEnd();
    }

    private static void fieldMethod(ClassWriter writer, String methodName, String descriptor, int popOpcode) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, "()V", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "VALUE", descriptor);
        method.visitInsn(popOpcode);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
    }

    private static void memberCallMethod(ClassWriter writer, String methodName, String owner) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "run", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
    }

    private static void memberFieldMethod(ClassWriter writer, String methodName, String owner) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, "()V", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, owner, "VALUE", "I");
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
    }

    private static void emptyMethodWithExceptions(ClassWriter writer, String name, String[] exceptions) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, exceptions);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeJar(Path path, Manifest manifest, List<JarContent> contents) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
             JarOutputStream jar = manifest == null
                     ? new JarOutputStream(output)
                     : new JarOutputStream(output, manifest)) {
            for (JarContent content : contents) {
                jar.putNextEntry(new JarEntry(content.name()));
                jar.write(content.bytes());
                jar.closeEntry();
            }
        }
    }

    private record JarContent(String name, byte[] bytes) {
    }
}
