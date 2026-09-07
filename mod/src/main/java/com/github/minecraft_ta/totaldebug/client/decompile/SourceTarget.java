package com.github.minecraft_ta.totaldebug.client.decompile;

import java.util.Objects;

/** A source location understood by TotalDebug without depending on JDT constants. */
public sealed interface SourceTarget {
    static SourceTarget wholeClass() {
        return WholeClass.INSTANCE;
    }

    static SourceTarget method(String identifier) {
        return new Method(identifier);
    }

    static SourceTarget field(String identifier) {
        return new Field(identifier);
    }

    enum WholeClass implements SourceTarget {
        INSTANCE
    }

    record Method(String identifier) implements SourceTarget {
        public Method {
            Objects.requireNonNull(identifier, "identifier");
            if (identifier.isBlank()) {
                throw new IllegalArgumentException("Method identifier must not be blank");
            }
        }
    }

    record Field(String identifier) implements SourceTarget {
        public Field {
            Objects.requireNonNull(identifier, "identifier");
            if (identifier.isBlank()) {
                throw new IllegalArgumentException("Field identifier must not be blank");
            }
        }
    }
}
