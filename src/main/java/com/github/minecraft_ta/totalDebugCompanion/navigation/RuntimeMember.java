package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;

import java.util.Objects;

/** An exact declaration inside a runtime class. */
public sealed interface RuntimeMember permits RuntimeMember.Field, RuntimeMember.Method {
    String ownerClassName();

    static RuntimeMember from(CodeSymbol symbol) {
        return switch (Objects.requireNonNull(symbol, "symbol")) {
            case CodeSymbol.ClassSymbol ignored -> throw new IllegalArgumentException("A class is not a member");
            case CodeSymbol.FieldSymbol field -> new Field(field.ownerClassName(), field.name());
            case CodeSymbol.MethodSymbol method -> new Method(
                    method.ownerClassName(),
                    method.name(),
                    method.descriptor()
            );
        };
    }

    record Field(String ownerClassName, String name) implements RuntimeMember {
        public Field {
            ownerClassName = requireText(ownerClassName, "ownerClassName");
            name = requireText(name, "name");
        }
    }

    record Method(String ownerClassName, String name, String descriptor) implements RuntimeMember {
        public Method {
            ownerClassName = requireText(ownerClassName, "ownerClassName");
            name = requireText(name, "name");
            descriptor = requireText(descriptor, "descriptor");
            if (!descriptor.startsWith("(")) {
                throw new IllegalArgumentException("descriptor must be a JVM method descriptor: " + descriptor);
            }
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
