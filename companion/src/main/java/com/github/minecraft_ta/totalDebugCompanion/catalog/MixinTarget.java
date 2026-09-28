package com.github.minecraft_ta.totalDebugCompanion.catalog;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A class that mixins target, as its bytecode declares it: its internal name, fields and methods, in the order the class
 * file lists them, which is the order Mixin selects them in.
 */
public record MixinTarget(String internalName, List<Field> fields, List<Method> methods) {
    /** A field the class declares, by name and type descriptor; a class file may hold two of one name. */
    public record Field(String name, String descriptor) {
        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** A method the class declares, and whether it is static. */
    public record Method(String name, String descriptor, boolean isStatic) {
        public Method {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    public MixinTarget {
        Objects.requireNonNull(internalName, "internalName");
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
    }

    /** The class {@code bytes} declare. */
    public static MixinTarget read(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        List<Field> fields = new ArrayList<>();
        List<Method> methods = new ArrayList<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                fields.add(new Field(name, descriptor));
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                methods.add(new Method(name, descriptor, (access & Opcodes.ACC_STATIC) != 0));
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new MixinTarget(reader.getClassName(), fields, methods);
    }
}
