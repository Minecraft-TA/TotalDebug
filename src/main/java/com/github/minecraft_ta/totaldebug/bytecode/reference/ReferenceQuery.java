package com.github.minecraft_ta.totaldebug.bytecode.reference;

import org.objectweb.asm.Type;

import java.util.Objects;

/** Identifies the exact JVM symbol whose bytecode references should be found. */
public sealed interface ReferenceQuery {
    static ClassReference classReference(String className) {
        return new ClassReference(className);
    }

    static FieldReference fieldReference(String ownerClassName, String name, String descriptor) {
        return new FieldReference(ownerClassName, name, descriptor);
    }

    static MethodReference methodReference(String ownerClassName, String name, String descriptor) {
        return new MethodReference(ownerClassName, name, descriptor);
    }

    record ClassReference(String className) implements ReferenceQuery {
        public ClassReference {
            className = requireBinaryClassName(className, "className");
        }
    }

    record FieldReference(String ownerClassName, String name, String descriptor) implements ReferenceQuery {
        public FieldReference {
            ownerClassName = requireBinaryClassName(ownerClassName, "ownerClassName");
            name = requireMemberName(name);
            descriptor = requireFieldDescriptor(descriptor);
        }
    }

    record MethodReference(String ownerClassName, String name, String descriptor) implements ReferenceQuery {
        public MethodReference {
            ownerClassName = requireBinaryClassName(ownerClassName, "ownerClassName");
            name = requireMemberName(name);
            descriptor = requireMethodDescriptor(descriptor);
        }
    }

    private static String requireBinaryClassName(String className, String parameterName) {
        Objects.requireNonNull(className, parameterName);
        if (className.isBlank()
                || className.indexOf('/') >= 0
                || className.indexOf('[') >= 0
                || className.indexOf(';') >= 0
                || className.chars().anyMatch(Character::isWhitespace)
                || className.startsWith(".")
                || className.endsWith(".")
                || className.contains("..")) {
            throw new IllegalArgumentException(parameterName + " must be a binary class name: " + className);
        }
        return className;
    }

    private static String requireMemberName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()
                || name.indexOf('.') >= 0
                || name.indexOf('/') >= 0
                || name.indexOf(';') >= 0
                || name.indexOf('[') >= 0
                || name.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("name must be a JVM member name: " + name);
        }
        return name;
    }

    private static String requireFieldDescriptor(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        try {
            Type type = Type.getType(descriptor);
            if (type.getSort() == Type.METHOD || type.getSort() == Type.VOID || !type.getDescriptor().equals(descriptor)) {
                throw new IllegalArgumentException("descriptor must be an exact JVM field descriptor: " + descriptor);
            }
            return descriptor;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("descriptor must be an exact JVM field descriptor: " + descriptor, exception);
        }
    }

    private static String requireMethodDescriptor(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        try {
            if (!descriptor.startsWith("(")) {
                throw new IllegalArgumentException("descriptor must be an exact JVM method descriptor: " + descriptor);
            }
            String canonicalDescriptor = Type.getMethodDescriptor(
                    Type.getReturnType(descriptor),
                    Type.getArgumentTypes(descriptor)
            );
            if (!canonicalDescriptor.equals(descriptor)) {
                throw new IllegalArgumentException("descriptor must be an exact JVM method descriptor: " + descriptor);
            }
            return descriptor;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("descriptor must be an exact JVM method descriptor: " + descriptor, exception);
        }
    }
}
