package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.jdt.stubs.IMethodStub;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.compiler.classfmt.JavaBinaryNames;
import java.util.Arrays;

public class JIndexResolvedBinaryMethod implements IMethodStub {

    private final JIndexBinaryMethod binaryMethod;
    private final String descriptor;
    private final String[] parameterTypes;

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
    public String[] getParameterNames() {
        return Arrays.stream(this.binaryMethod.getArgumentNames()).map(String::new).toArray(String[]::new);
    }

    @Override
    public String[] getRawParameterNames() {
        return getParameterNames();
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
