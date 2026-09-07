package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.client.decompile.SourceTarget;
import java.util.Objects;
import com.github.minecraft_ta.totaldebug.protocol.navigation.SourceTargetKind;

/** Converts game source targets to stable Companion navigation codes. */
final class CompanionSourceTargetCodec {

    private CompanionSourceTargetCodec() {
    }

    static WireTarget encode(SourceTarget sourceTarget) {
        Objects.requireNonNull(sourceTarget, "sourceTarget");
        return switch (sourceTarget) {
            case SourceTarget.WholeClass ignored -> new WireTarget(SourceTargetKind.WHOLE_CLASS, "");
            case SourceTarget.Method method -> new WireTarget(SourceTargetKind.METHOD, method.identifier());
            case SourceTarget.Field field -> new WireTarget(SourceTargetKind.FIELD, field.identifier());
        };
    }

    record WireTarget(int javaElementType, String identifier) {
        WireTarget {
            Objects.requireNonNull(identifier, "identifier");
        }
    }
}
