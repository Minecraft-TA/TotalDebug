package com.github.minecraft_ta.totalDebugCompanion.catalog;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;

/** Mixin classes as a mod's file holds them, built with ASM. */
public final class MixinFixtures {
    /**
     * A method of a mixin: its name, the annotation on it by descriptor, and the attribute naming its target with that
     * target, or a null attribute for an annotation without one, such as an accessor named after its method.
     */
    public record Method(String name, String annotation, String attribute, String target, String descriptor) {
        public Method(String name, String annotation, String attribute, String target) {
            this(name, annotation, attribute, target, "()V");
        }
    }

    private MixinFixtures() {
    }

    /** A class named {@code name} with {@code @Mixin(target)}, its priority when not the default, and {@code methods}. */
    public static byte[] mixinClass(String name, String target, int priority, List<Method> methods) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType(target.replace('.', '/')));
        value.visitEnd();
        if (priority != 1000) mixin.visit("priority", priority);
        mixin.visitEnd();
        for (Method method : methods) {
            MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, method.name(), method.descriptor(), null, null);
            AnnotationVisitor annotation = visitor.visitAnnotation(method.annotation(), false);
            if (method.attribute() != null) {
                AnnotationVisitor array = annotation.visitArray(method.attribute());
                array.visit(null, method.target());
                array.visitEnd();
            }
            annotation.visitEnd();
            visitor.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}
