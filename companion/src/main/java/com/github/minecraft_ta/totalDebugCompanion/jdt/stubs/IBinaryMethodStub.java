package com.github.minecraft_ta.totalDebugCompanion.jdt.stubs;

import org.eclipse.jdt.internal.compiler.env.IBinaryAnnotation;
import org.eclipse.jdt.internal.compiler.env.IBinaryMethod;
import org.eclipse.jdt.internal.compiler.env.IBinaryTypeAnnotation;

public interface IBinaryMethodStub extends IBinaryMethod {

    @Override
    default IBinaryAnnotation[] getAnnotations() {
        return null;
    }

    @Override
    default Object getDefaultValue() {
        return null;
    }

    @Override
    default char[][] getExceptionTypeNames() {
        return null;
    }

    @Override
    default char[] getGenericSignature() {
        return null;
    }

    @Override
    default char[] getMethodDescriptor() {
        return null;
    }

    @Override
    default IBinaryAnnotation[] getParameterAnnotations(int index, char[] classFileName) {
        return null;
    }

    @Override
    default int getAnnotatedParametersCount() {
        return 0;
    }

    @Override
    default char[] getSelector() {
        return null;
    }

    @Override
    default long getTagBits() {
        return 0;
    }

    @Override
    default boolean isClinit() {
        return false;
    }

    @Override
    default IBinaryTypeAnnotation[] getTypeAnnotations() {
        return null;
    }

    @Override
    default int getModifiers() {
        return 0;
    }

    @Override
    default boolean isConstructor() {
        return false;
    }

    @Override
    default char[][] getArgumentNames() {
        return null;
    }
}
