package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import org.eclipse.jdt.core.compiler.IProblem;
import java.util.Objects;

/** Generated compilation text, editor mapping, and language permissions for that source. */
public record JavaEditorSource(String text, JavaSourceMap sourceMap, boolean privilegedAccess) {
    public JavaEditorSource {
        text = Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sourceMap, "sourceMap");
    }

    public static JavaEditorSource identity(String text) {
        return new JavaEditorSource(text, JavaSourceMap.IDENTITY, false);
    }

    static boolean isAccessProblem(int problemId) {
        return switch (problemId) {
            case IProblem.NotVisibleField, IProblem.NotVisibleMethod, IProblem.NotVisibleConstructor,
                    IProblem.NotAccessibleField, IProblem.NotAccessibleMethod, IProblem.NotAccessibleConstructor -> true;
            default -> false;
        };
    }
}
