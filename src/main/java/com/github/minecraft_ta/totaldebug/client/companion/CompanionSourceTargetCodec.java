package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.client.decompile.SourceTarget;

import java.util.Objects;

/** Converts TotalDebug source targets to the JDT element values used by the existing Companion wire format. */
final class CompanionSourceTargetCodec {
    private static final int WHOLE_CLASS = -1;
    private static final int JDT_FIELD = 8;
    private static final int JDT_METHOD = 9;

    private CompanionSourceTargetCodec() {
    }

    static WireTarget encode(SourceTarget sourceTarget) {
        Objects.requireNonNull(sourceTarget, "sourceTarget");
        return switch (sourceTarget) {
            case SourceTarget.WholeClass ignored -> new WireTarget(WHOLE_CLASS, "");
            case SourceTarget.Method method -> new WireTarget(JDT_METHOD, method.identifier());
            case SourceTarget.Field field -> new WireTarget(JDT_FIELD, field.identifier());
        };
    }

    static SourceTarget decode(int javaElementType, String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return switch (javaElementType) {
            case WHOLE_CLASS -> SourceTarget.wholeClass();
            case JDT_METHOD -> SourceTarget.method(identifier);
            case JDT_FIELD -> SourceTarget.field(identifier);
            default -> throw new IllegalArgumentException("unknown JDT element type " + javaElementType);
        };
    }

    record WireTarget(int javaElementType, String identifier) {
        WireTarget {
            Objects.requireNonNull(identifier, "identifier");
        }
    }
}
