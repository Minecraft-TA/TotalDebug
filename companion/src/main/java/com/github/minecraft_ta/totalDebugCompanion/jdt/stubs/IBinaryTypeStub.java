package com.github.minecraft_ta.totalDebugCompanion.jdt.stubs;

import org.eclipse.jdt.internal.compiler.env.*;
import org.eclipse.jdt.internal.compiler.lookup.BinaryTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;

public interface IBinaryTypeStub extends IBinaryType {

    @Override
    default IBinaryAnnotation[] getAnnotations() {
        return null;
    }

    @Override
    default IBinaryTypeAnnotation[] getTypeAnnotations() {
        return null;
    }

    @Override
    default char[] getEnclosingMethod() {
        return null;
    }

    @Override
    default char[] getEnclosingTypeName() {
        return null;
    }

    @Override
    default IBinaryField[] getFields() {
        return null;
    }

    @Override
    default IRecordComponent[] getRecordComponents() {
        return null;
    }

    @Override
    default char[] getModule() {
        return null;
    }

    @Override
    default char[] getGenericSignature() {
        return null;
    }

    @Override
    default char[][] getInterfaceNames() {
        return null;
    }

    @Override
    default IBinaryNestedType[] getMemberTypes() {
        return null;
    }

    @Override
    default IBinaryMethod[] getMethods() {
        return null;
    }

    @Override
    default char[][][] getMissingTypeNames() {
        return null;
    }

    @Override
    default char[] getName() {
        return null;
    }

    @Override
    default char[] getSourceName() {
        return null;
    }

    @Override
    default char[] getSuperclassName() {
        return null;
    }

    @Override
    default long getTagBits() {
        return 0;
    }

    @Override
    default boolean isAnonymous() {
        return false;
    }

    @Override
    default boolean isLocal() {
        return false;
    }

    @Override
    default boolean isRecord() {
        return false;
    }

    @Override
    default boolean isMember() {
        return false;
    }

    @Override
    default char[] sourceFileName() {
        return null;
    }

    @Override
    default ITypeAnnotationWalker enrichWithExternalAnnotationsFor(ITypeAnnotationWalker walker, Object member, LookupEnvironment environment) {
        return null;
    }

    @Override
    default BinaryTypeBinding.ExternalAnnotationStatus getExternalAnnotationStatus() {
        return null;
    }

    @Override
    default int getModifiers() {
        return 0;
    }

    @Override
    default boolean isBinaryType() {
        return false;
    }

    @Override
    default char[] getFileName() {
        return null;
    }
}
