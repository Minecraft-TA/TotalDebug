package com.github.minecraft_ta.totalDebugCompanion.jdt.symbol;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;

import java.util.Objects;

/** An exact JVM symbol selected in a Companion source editor. */
public sealed interface CodeSymbol permits CodeSymbol.ClassSymbol, CodeSymbol.FieldSymbol, CodeSymbol.MethodSymbol {

    String ownerClassName();

    String displayName();

    ReferenceQuery referenceQuery();

    record ClassSymbol(String className) implements CodeSymbol {
        public ClassSymbol {
            requireText(className, "className");
        }

        @Override
        public String ownerClassName() {
            return this.className;
        }

        @Override
        public String displayName() {
            return this.className;
        }

        @Override
        public ReferenceQuery referenceQuery() {
            return ReferenceQuery.classReference(this.className);
        }
    }

    record FieldSymbol(String ownerClassName, String name, String descriptor) implements CodeSymbol {
        public FieldSymbol {
            requireMember(ownerClassName, name, descriptor);
        }

        @Override
        public String displayName() {
            return this.ownerClassName + '#' + this.name;
        }

        @Override
        public ReferenceQuery referenceQuery() {
            return ReferenceQuery.fieldReference(this.ownerClassName, this.name, this.descriptor);
        }
    }

    record MethodSymbol(String ownerClassName, String name, String descriptor) implements CodeSymbol {
        public MethodSymbol {
            requireMember(ownerClassName, name, descriptor);
        }

        @Override
        public String displayName() {
            String displayMethod = "<init>".equals(this.name)
                    ? simpleClassName(this.ownerClassName)
                    : this.name;
            return this.ownerClassName + '#' + displayMethod + this.descriptor;
        }

        @Override
        public ReferenceQuery referenceQuery() {
            return ReferenceQuery.methodReference(this.ownerClassName, this.name, this.descriptor);
        }
    }

    private static void requireMember(String ownerClassName, String name, String descriptor) {
        requireText(ownerClassName, "ownerClassName");
        requireText(name, "name");
        requireText(descriptor, "descriptor");
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String simpleClassName(String binaryName) {
        int packageSeparator = binaryName.lastIndexOf('.');
        return binaryName.substring(packageSeparator + 1).replace('$', '.');
    }
}
