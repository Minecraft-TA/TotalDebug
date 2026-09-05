package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.jdt.stubs.IMethodStub;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.compiler.classfmt.JavaBinaryNames;

public class JIndexResolvedBinaryMethod implements IMethodStub {

    private final JIndexBinaryMethod binaryMethod;
    private final String descriptor;
    private String[] parameterTypes;

    public JIndexResolvedBinaryMethod(JIndexBinaryMethod binaryMethod) {
        this.binaryMethod = binaryMethod;
        this.descriptor = new String(this.binaryMethod.getMethodDescriptor());
        this.parameterTypes = Signature.getParameterTypes(descriptor);
    }

    @Override
    public String[] getParameterTypes() {
        return parameterTypes;
    }

    @Override
    public String getElementName() {
        return new String(this.binaryMethod.getSelector());
    }

    @Override
    public String getSignature() {
        return this.descriptor;
    }

    @Override
    public int getNumberOfParameters() {
        return this.parameterTypes.length;
    }

    @Override
    public int getFlags() {
        return this.binaryMethod.getModifiers();
    }

    @Override
    public boolean isConstructor() {
        return JavaBinaryNames.isConstructor(this.binaryMethod.getSelector());
    }
}
