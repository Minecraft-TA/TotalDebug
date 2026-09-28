package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.util.Objects;

/**
 * A member of a target class that a mixin changes: the class itself, a field, or a method. Two changes meet where the
 * member one names reaches the member of the other's row ({@link #reaches}); nothing else decides it.
 */
public sealed interface MixinMember {
    /** The class itself, which a mixin adds members or interfaces to. */
    record Whole() implements MixinMember {
        @Override
        public String name() {
            return "";
        }

        @Override
        public String shown() {
            return "The class";
        }

        @Override
        public String reference() {
            return "";
        }

        @Override
        public boolean reaches(MixinMember row) {
            return row instanceof Whole;
        }
    }

    /** A field by its name, which accessors reach; Java code does not overload fields. */
    record Field(String name) implements MixinMember {
        public Field {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String shown() {
            return this.name;
        }

        @Override
        public String reference() {
            return this.name;
        }

        @Override
        public boolean reaches(MixinMember row) {
            return row instanceof Field field && field.name.equals(this.name);
        }
    }

    /**
     * A method by its name, which may be a wildcard such as {@code render*}, and its descriptor, which picks one
     * overload, or empty for every overload of the name.
     */
    record Method(String name, String descriptor) implements MixinMember {
        public Method {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }

        /** Whether the name is a wildcard, naming several methods. */
        public boolean wildcard() {
            return this.name.contains("*");
        }

        @Override
        public String shown() {
            return this.name + this.descriptor;
        }

        @Override
        public String reference() {
            return this.name + this.descriptor;
        }

        /** A method of a name it names or its wildcard matches, and of its descriptor, or of any when it gives none. */
        @Override
        public boolean reaches(MixinMember row) {
            if (!(row instanceof Method method)) return false;
            boolean named = this.name.equals(method.name) || wildcard() && matches(this.name, method.name);
            return named && (this.descriptor.isEmpty() || this.descriptor.equals(method.descriptor));
        }

        /** Whether {@code name} matches {@code pattern}, in which each {@code *} stands for any text. */
        private static boolean matches(String pattern, String name) {
            String[] parts = pattern.split("\\*", -1);
            if (!name.startsWith(parts[0])) return false;
            int at = parts[0].length();
            for (int index = 1; index < parts.length - 1; index++) {
                int found = name.indexOf(parts[index], at);
                if (found < 0) return false;
                at = found + parts[index].length();
            }
            String last = parts[parts.length - 1];
            return name.length() - last.length() >= at && name.endsWith(last);
        }
    }

    /** The member's name, empty for the class itself. */
    String name();

    /** The member as a row shows it: a method with its descriptor where it gives one. */
    String shown();

    /** The member in a reference after {@code #}, empty for the class itself. */
    String reference();

    /** Whether a change to this member changes {@code row}, the member a row stands for. */
    boolean reaches(MixinMember row);
}
