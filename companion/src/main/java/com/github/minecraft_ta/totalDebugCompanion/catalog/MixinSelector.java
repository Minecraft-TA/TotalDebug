package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What a mixin's change names in its target, as Mixin's selectors name it. {@link #select} resolves it against the
 * members the target declares as Mixin does, so two changes meet exactly on the members both select; selectors are never
 * compared with each other.
 */
public sealed interface MixinSelector {
    /** The class itself, which a mixin adds members or interfaces to. */
    record Whole() implements MixinSelector {
        @Override
        public String shown() {
            return "The class";
        }

        @Override
        public List<MixinMember> select(MixinTarget target, boolean staticHandler) {
            return List.of(new MixinMember.Whole());
        }
    }

    /** A field by its name and the type its accessor gets or sets, as an accessor names it. */
    record Field(String name, String descriptor) implements MixinSelector {
        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }

        @Override
        public String shown() {
            return this.name + ":" + this.descriptor;
        }

        @Override
        public List<MixinMember> select(MixinTarget target, boolean staticHandler) {
            MixinTarget.Field field = new MixinTarget.Field(this.name, this.descriptor);
            return target.fields().contains(field) ? List.of(new MixinMember.Field(this.name, this.descriptor)) : List.of();
        }
    }

    /**
     * Methods by name, or every method for an empty name, and by descriptor, or every overload for an empty one; at most
     * {@code limit} of them, in the order the class declares them. Without a quantifier a selector selects one method, the
     * first that matches; {@code *} and {@code +} select every match.
     */
    record Method(String name, String descriptor, int limit) implements MixinSelector {
        public Method {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }

        /** The one method of this name and descriptor, as an overwrite or invoker names it. */
        public Method(String name, String descriptor) {
            this(name, descriptor, 1);
        }

        @Override
        public String shown() {
            return (this.name.isEmpty() ? "*" : this.name) + this.descriptor;
        }

        @Override
        public List<MixinMember> select(MixinTarget target, boolean staticHandler) {
            List<MixinMember> selected = new ArrayList<>();
            int matched = 0;
            for (MixinTarget.Method method : target.methods()) {
                if (matched >= this.limit) break;
                if (!this.name.isEmpty() && !this.name.equals(method.name())) continue;
                if (!this.descriptor.isEmpty() && !this.descriptor.equals(method.descriptor())) continue;
                matched++;
                if (selectable(method, this.limit, staticHandler)) selected.add(new MixinMember.Method(method.name(), method.descriptor()));
            }
            return selected;
        }
    }

    /**
     * Methods whose owner, name or descriptor the regular expressions find a match in, as a selector such as
     * {@code /^render/} or {@code owner=/Level$/ name=/tick/} names them; an empty pattern is not given. As in Mixin, the
     * last pattern given decides.
     */
    record Matching(String owner, String name, String descriptor) implements MixinSelector {
        public Matching {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }

        @Override
        public String shown() {
            if (this.owner.isEmpty() && this.descriptor.isEmpty()) return "/" + this.name + "/";
            List<String> parts = new ArrayList<>();
            if (!this.owner.isEmpty()) parts.add("owner=/" + this.owner + "/");
            if (!this.name.isEmpty()) parts.add("name=/" + this.name + "/");
            if (!this.descriptor.isEmpty()) parts.add("desc=/" + this.descriptor + "/");
            return String.join(" ", parts);
        }

        @Override
        public List<MixinMember> select(MixinTarget target, boolean staticHandler) {
            List<MixinMember> selected = new ArrayList<>();
            for (MixinTarget.Method method : target.methods()) {
                if (matches(target.internalName(), method) && selectable(method, Integer.MAX_VALUE, staticHandler)) {
                    selected.add(new MixinMember.Method(method.name(), method.descriptor()));
                }
            }
            return selected;
        }

        private boolean matches(String owner, MixinTarget.Method method) {
            boolean matched = false;
            String[] patterns = {this.owner, this.name, this.descriptor};
            String[] values = {owner, method.name(), method.descriptor()};
            for (int index = 0; index < patterns.length; index++) {
                if (!patterns[index].isEmpty()) matched = compiled(patterns[index]).matcher(values[index]).find();
            }
            return matched;
        }

        private static Pattern compiled(String pattern) {
            try {
                return Pattern.compile(pattern);
            } catch (PatternSyntaxException invalid) {
                // Mixin matches every value then.
                return Pattern.compile(".*");
            }
        }
    }

    /** A dynamic selector such as {@code @Foo(bar)}, which only the game resolves. */
    record Dynamic(String text) implements MixinSelector {
        public Dynamic {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String shown() {
            return this.text;
        }

        @Override
        public List<MixinMember> select(MixinTarget target, boolean staticHandler) {
            return List.of();
        }
    }

    /** The first of several selectors that selects anything, as an overwrite takes its own name, then each alias. */
    record First(List<MixinSelector> choices) implements MixinSelector {
        public First {
            choices = List.copyOf(choices);
            if (choices.isEmpty()) throw new IllegalArgumentException("choices must not be empty");
        }

        @Override
        public String shown() {
            return this.choices.getFirst().shown();
        }

        @Override
        public List<MixinMember> select(MixinTarget target, boolean staticHandler) {
            for (MixinSelector choice : this.choices) {
                List<MixinMember> selected = choice.select(target, staticHandler);
                if (!selected.isEmpty()) return selected;
            }
            return List.of();
        }
    }

    /** The selector as a mod wrote it, for a change that selects nothing. */
    String shown();

    /**
     * The members of {@code target} this selects, for a change whose handler method is static or not: a selector of
     * several methods passes over the static ones for a handler that is not, as Mixin does.
     */
    List<MixinMember> select(MixinTarget target, boolean staticHandler);

    private static boolean selectable(MixinTarget.Method method, int limit, boolean staticHandler) {
        return limit <= 1 || staticHandler || !method.isStatic();
    }
}
