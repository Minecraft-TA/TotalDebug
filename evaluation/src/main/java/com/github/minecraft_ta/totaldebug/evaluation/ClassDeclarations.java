package com.github.minecraft_ta.totaldebug.evaluation;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact declaration identity, deliberately excluding implementation and debug information. */
public final class ClassDeclarations {
    private ClassDeclarations() {}

    public static String fingerprint(byte[] bytecode) {
        // A fresh constant pool omits constants used only by method bodies.
        var writer = new ClassWriter(0);
        var reader = new ClassReader(bytecode);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                // Referencing another nested type in a method body also creates an InnerClasses entry.
                // Keep this class's own nesting/access metadata and its declared member classes only.
                if (reader.getClassName().equals(name) || reader.getClassName().equals(outerName)) {
                    super.visitInnerClass(name, outerName, innerName, access);
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return HexFormat.of().formatHex(digest().digest(writer.toByteArray()));
    }

    public static String archiveFingerprint(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new AssertionError(exception); }
    }
}
