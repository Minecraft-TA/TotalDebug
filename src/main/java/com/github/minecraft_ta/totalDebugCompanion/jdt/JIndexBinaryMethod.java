package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.jdt.stubs.IBinaryMethodStub;
import com.github.tth05.jindex.IndexedMethod;
import org.eclipse.jdt.internal.compiler.classfmt.JavaBinaryNames;

public class JIndexBinaryMethod implements IBinaryMethodStub {

    private final IndexedMethod indexedMethod;

    public JIndexBinaryMethod(IndexedMethod indexedMethod) {
        this.indexedMethod = indexedMethod;
    }

    @Override
    public char[] getSelector() {
        return this.indexedMethod.getName().toCharArray();
    }

    @Override
    public int getModifiers() {
        return this.indexedMethod.getAccessFlags();
    }

    @Override
    public char[] getGenericSignature() {
        var str = this.indexedMethod.getGenericSignatureString();
        if (str == null)
            return null;
        str = str.replace(":Ljava/lang/Object;:", "::");
        return str.toCharArray();
    }

    @Override
    public char[] getMethodDescriptor() {
        return this.indexedMethod.getDescriptorString().toCharArray();
    }

    @Override
    public char[][] getExceptionTypeNames() {
        var exceptions = this.indexedMethod.getExceptions();
        int resolvedCount = 0;
        for (var exception : exceptions) {
            if (exception != null) {
                resolvedCount++;
            }
        }
        if (resolvedCount == 0) {
            return null;
        }
        var names = new char[resolvedCount][];
        int outputIndex = 0;
        for (var exception : exceptions) {
            if (exception != null) {
                names[outputIndex++] = exception.getNameWithPackage().toCharArray();
            }
        }
        return names;
    }

    @Override
    public boolean isClinit() {
        return JavaBinaryNames.isClinit(getSelector());
    }

    @Override
    public boolean isConstructor() {
        return JavaBinaryNames.isConstructor(getSelector());
    }
}
