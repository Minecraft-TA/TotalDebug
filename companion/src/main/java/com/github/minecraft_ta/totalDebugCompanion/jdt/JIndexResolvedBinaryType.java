package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.ClassFileImpl;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import com.github.tth05.jindex.IndexedClass;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.core.JavaElement;
import org.eclipse.jdt.internal.core.ResolvedBinaryType;
import org.eclipse.jdt.internal.core.TypeParameter;
import org.eclipse.jdt.internal.core.TypeParameterElementInfo;

import java.util.Arrays;

public class JIndexResolvedBinaryType extends ResolvedBinaryType {

    private final JIndexBinaryType binaryType;

    public JIndexResolvedBinaryType(IndexedClass indexedClass) {
        super(new ClassFileImpl(indexedClass), indexedClass.getName(), indexedClass.getNameWithPackage());
        this.binaryType = new JIndexBinaryType(indexedClass);
        ((ClassFileImpl) getParent()).setType(this);
    }

    @Override
    public ITypeParameter[] getTypeParameters() {
        var signature = this.binaryType.getGenericSignature();
        if (signature == null)
            return new ITypeParameter[0];

        char[][] typeParameterSignatures = Signature.getTypeParameters(signature);
        var typeParameters = new ITypeParameter[typeParameterSignatures.length];

        for (int i = 0; i < typeParameterSignatures.length; i++) {
            char[] typeParameterSignature = typeParameterSignatures[i];
            CharOperation.replace(typeParameterSignature, '/', '.');
            char[][] typeParameterBoundSignatures = Signature.getTypeParameterBounds(typeParameterSignature);

            int boundLength = typeParameterBoundSignatures.length;
            char[][] typeParameterBounds = new char[boundLength][];
            for (int j = 0; j < boundLength; j++) {
                typeParameterBounds[j] = Signature.toCharArray(typeParameterBoundSignatures[j]);
            }

            TypeParameterElementInfo info = new TypeParameterElementInfo();
            info.bounds = typeParameterBounds;
            info.boundsSignatures = typeParameterBoundSignatures;
            TypeParameter typeParameter = new TypeParameter(this, new String(Signature.getTypeVariable(typeParameterSignature))) {
                @Override
                public TypeParameterElementInfo getElementInfo() {
                    return info;
                }
            };
            typeParameters[i] = typeParameter;
        }

        return typeParameters;
    }

    @Override
    public JavaElement getParent() {
        return super.getParent();
    }

    @Override
    public JIndexBinaryType getElementInfo() {
        return this.binaryType;
    }

    @Override
    public boolean isBinary() {
        return true;
    }

    @Override
    public IType getDeclaringType() {
        var enclosingTypeName = this.binaryType.getEnclosingTypeName();
        if (enclosingTypeName == null)
            return null;
        var typeStr = new String(enclosingTypeName);
        var parts = CodeUtils.splitTypeName(typeStr);
        return new JIndexResolvedBinaryType(CompanionClassIndex.get().findClass(parts[0], parts[1]));
    }

    @Override
    public IMethod[] getMethods() {
        var methods = this.binaryType.getMethods();
        var resolvedMethods = new IMethod[methods.length];

        var invalid = 0;
        for (int i = 0; i < methods.length; i++) {
            try {
                resolvedMethods[i] = new JIndexResolvedBinaryMethod((JIndexBinaryMethod) methods[i]);
            } catch (IllegalArgumentException e) {
                invalid++;
            }
        }

        if (invalid > 0) {
            var newMethods = new IMethod[methods.length - invalid];
            var index = 0;
            for (var method : resolvedMethods) {
                if (method != null)
                    newMethods[index++] = method;
            }
            return newMethods;
        }

        return resolvedMethods;
    }

    @Override
    public IType getType(String typeName) {
        var types = getTypes();
        if (types == null)
            return null;

        for (IType type : types) {
            var name = type.getElementName();
            var idx = name.lastIndexOf('$');
            if (idx == -1 && name.equals(typeName))
                return type;
            else if (idx != -1 && name.regionMatches(idx + 1, typeName, 0, typeName.length()))
                return type;
        }
        return null;
    }

    @Override
    public IType[] getTypes() {
        var memberClasses = this.binaryType.getMemberClasses();
        if (memberClasses.length == 0)
            return null;
        return Arrays.stream(memberClasses).map(JIndexResolvedBinaryType::new).toArray(IType[]::new);
    }

    @Override
    public String getFullyQualifiedName() {
        return this.binaryType.getFullyQualifiedName();
    }

    @Override
    public IOrdinaryClassFile getClassFile() {
        return (IOrdinaryClassFile) getParent();
    }
}
