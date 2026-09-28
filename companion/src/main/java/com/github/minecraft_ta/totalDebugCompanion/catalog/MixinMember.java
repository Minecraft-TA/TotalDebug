package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A member of a target class that a mixin changes, as Mixin's target selectors name it: the class itself, a field, a
 * method by name, or methods whose names match a pattern. Two changes meet where the member one names reaches the member
 * of the other's row ({@link #reaches}); nothing else decides it.
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
        public boolean reaches(MixinMember row) {
            return row instanceof Whole;
        }

        @Override
        public int precision() {
            return 2;
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
        public boolean reaches(MixinMember row) {
            return row instanceof Field field && field.name.equals(this.name);
        }

        @Override
        public int precision() {
            return 2;
        }
    }

    /**
     * A method by its name, empty for every method, as the selector {@code *} names them, and its descriptor, which picks
     * one overload, or empty for every overload of the name.
     */
    record Method(String name, String descriptor) implements MixinMember {
        public Method {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }

        @Override
        public String shown() {
            return (this.name.isEmpty() ? "Every method" : this.name) + this.descriptor;
        }

        /** A method of its name, or of any when it names none, and of its descriptor, or of any when it gives none. */
        @Override
        public boolean reaches(MixinMember row) {
            return row instanceof Method method && (this.name.isEmpty() || this.name.equals(method.name))
                    && (this.descriptor.isEmpty() || this.descriptor.equals(method.descriptor));
        }

        @Override
        public int precision() {
            return this.name.isEmpty() ? 0 : this.descriptor.isEmpty() ? 1 : 2;
        }
    }

    /** Methods whose names the regular expression {@code pattern} finds a match in, as a selector {@code /pattern/} names them. */
    record Matching(String pattern) implements MixinMember {
        private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

        public Matching {
            Objects.requireNonNull(pattern, "pattern");
        }

        @Override
        public String name() {
            return this.pattern;
        }

        @Override
        public String shown() {
            return "/" + this.pattern + "/";
        }

        @Override
        public boolean reaches(MixinMember row) {
            if (row instanceof Matching matching) return matching.pattern.equals(this.pattern);
            if (!(row instanceof Method method) || method.name().isEmpty()) return false;
            Pattern compiled = COMPILED.computeIfAbsent(this.pattern, text -> {
                try {
                    return Pattern.compile(text);
                } catch (PatternSyntaxException invalid) {
                    // Mixin itself matches every name then.
                    return Pattern.compile(".*");
                }
            });
            return compiled.matcher(method.name()).find();
        }

        @Override
        public int precision() {
            return 0;
        }
    }

    /** The member's name, empty for the class itself or every method. */
    String name();

    /** The member as a row shows it and a reference names it: a method with its descriptor where it gives one. */
    String shown();

    /** Whether a change to this member changes {@code row}, the member a row stands for. */
    boolean reaches(MixinMember row);

    /**
     * How closely the member names what it changes: a pattern or every method least, a method named without its
     * descriptor more, and an overload, a field or the class exactly. A change joins the rows of the more exact members it
     * reaches, and is a row of its own only where it reaches none.
     */
    int precision();
}
