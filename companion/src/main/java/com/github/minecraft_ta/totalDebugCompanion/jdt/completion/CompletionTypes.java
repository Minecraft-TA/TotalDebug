package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import org.eclipse.jdt.internal.compiler.lookup.CaptureBinding;
import org.eclipse.jdt.internal.compiler.lookup.ParameterizedTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import java.util.Arrays;

/** Preserve source wildcards when rendering types captured by completion's expression resolver. */
final class CompletionTypes {
    private CompletionTypes() { }

    static TypeBinding uncapture(TypeBinding type) {
        if (type instanceof CaptureBinding capture) return capture.wildcard;
        if (type instanceof ParameterizedTypeBinding parameterized && parameterized.arguments != null) {
            return parameterized.environment.createParameterizedType(parameterized.genericType(),
                    Arrays.stream(parameterized.arguments).map(CompletionTypes::uncapture).toArray(TypeBinding[]::new), parameterized.enclosingType());
        }
        return type;
    }

}
