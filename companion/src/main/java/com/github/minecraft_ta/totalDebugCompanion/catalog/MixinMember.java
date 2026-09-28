package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.util.Objects;

/**
 * A member of a target class that mixins change, as the class declares it: the class itself, a field, or one method.
 * Changes meet on a member when their selectors both select it ({@link MixinSelector#select}).
 */
public sealed interface MixinMember {
    /** The class itself, which a mixin adds members or interfaces to. */
    record Whole() implements MixinMember {
        @Override
        public String shown() {
            return "The class";
        }
    }

    /** A field, by its name and type descriptor, as Mixin writes it: {@code speed:I}. */
    record Field(String name, String descriptor) implements MixinMember {
        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }

        @Override
        public String shown() {
            return this.name + ":" + this.descriptor;
        }
    }

    /** One method, by its name and descriptor. */
    record Method(String name, String descriptor) implements MixinMember {
        public Method {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }

        @Override
        public String shown() {
            return this.name + this.descriptor;
        }
    }

    /** The member as a row shows it and a reference names it after {@code #}. */
    String shown();
}
