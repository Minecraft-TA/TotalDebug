package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.jdt.stubs.IBinaryTypeStub;
import com.github.tth05.jindex.IndexedClass;
import com.github.tth05.jindex.InnerClassType;
import org.eclipse.jdt.internal.compiler.env.*;
import org.eclipse.jdt.internal.compiler.lookup.BinaryTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;

import java.util.Arrays;

public class JIndexBinaryType implements IBinaryTypeStub {

    private final IndexedClass indexedClass;

    public JIndexBinaryType(IndexedClass indexedClass) {
        this.indexedClass = indexedClass;
    }

    public IndexedClass[] getMemberClasses() {
        return this.indexedClass.getMemberClasses();
    }

    public String getFullyQualifiedName() {
        return this.indexedClass.getNameWithPackageDot();
    }

    @Override
    public IBinaryAnnotation[] getAnnotations() {
        return ITypeAnnotationWalker.NO_ANNOTATIONS;
    }

    @Override
    public char[] getName() {
        return this.indexedClass.getNameWithPackage().toCharArray();
    }

    @Override
    public char[] getEnclosingMethod() {
        //TODO: Handle this better? `Blocks.` crashes otherwise. "scala.tools.nsc.backend.jvm.GenASM$newNormal$$anonfun$elimUnreachableBlocks$4$$anonfun$apply$28"
        if (this.indexedClass.getEnclosingClass() == null)
            return null;

        var desc = this.indexedClass.getEnclosingMethodNameAndDesc();
        return desc == null ? null : desc.toCharArray();
    }

    @Override
    public char[] getEnclosingTypeName() {
        var enclosingClass = this.indexedClass.getEnclosingClass();
        return enclosingClass == null ? null : enclosingClass.getNameWithPackage().toCharArray();
    }

    @Override
    public char[] getSuperclassName() {
        var superClass = this.indexedClass.getSuperClass();
        return superClass == null ? null : superClass.getNameWithPackage().toCharArray();
    }

    @Override
    public char[][] getInterfaceNames() {
        var interfaces = this.indexedClass.getInterfaces();
        if (interfaces == null)
            return null;

        var array = new char[interfaces.length][];
        for (int i = 0; i < interfaces.length; i++) {
            array[i] = interfaces[i].getNameWithPackage().toCharArray();
        }

        return array;
    }

    @Override
    public char[] getSourceName() {
        return this.indexedClass.getSourceName().toCharArray();
    }

    @Override
    public char[] getGenericSignature() {
        var str = this.indexedClass.getGenericSignatureString();
        return str == null ? null : str.toCharArray();
    }

    @Override
    public IBinaryField[] getFields() {
        var fields = this.indexedClass.getFields();
        var array = new JIndexBinaryField[fields.length];
        for (int i = 0; i < fields.length; i++) {
            array[i] = new JIndexBinaryField(fields[i]);
        }
        return array;
    }

    @Override
    public IBinaryMethod[] getMethods() {
        var methods = this.indexedClass.getMethods();
        var array = new JIndexBinaryMethod[methods.length];
        for (int i = 0; i < methods.length; i++) {
            array[i] = new JIndexBinaryMethod(methods[i]);
        }
        return array;
    }

    @Override
    public IBinaryNestedType[] getMemberTypes() {
        var memberClasses = this.indexedClass.getMemberClasses();
        var enclosingName = this.indexedClass.getNameWithPackage().toCharArray();
        return memberClasses.length == 0 ? null : Arrays.stream(memberClasses).map(c -> new IBinaryNestedType() {
            @Override
            public char[] getEnclosingTypeName() {
                return enclosingName;
            }

            @Override
            public int getModifiers() {
                return c.getAccessFlags();
            }

            @Override
            public char[] getName() {
                return c.getNameWithPackage().toCharArray();
            }
        }).toArray(IBinaryNestedType[]::new);
    }

    @Override
    public ITypeAnnotationWalker enrichWithExternalAnnotationsFor(ITypeAnnotationWalker walker, Object member, LookupEnvironment environment) {
        return ITypeAnnotationWalker.EMPTY_ANNOTATION_WALKER;
    }

    @Override
    public BinaryTypeBinding.ExternalAnnotationStatus getExternalAnnotationStatus() {
        return BinaryTypeBinding.ExternalAnnotationStatus.NO_EEA_FILE;
    }

    @Override
    public int getModifiers() {
        return this.indexedClass.getAccessFlags();
    }

    @Override
    public boolean isBinaryType() {
        return true;
    }

    @Override
    public boolean isAnonymous() {
        return this.indexedClass.getInnerClassType() == InnerClassType.ANONYMOUS;
    }

    @Override
    public boolean isLocal() {
        return this.indexedClass.getInnerClassType() == InnerClassType.LOCAL;
    }

    @Override
    public boolean isMember() {
        return this.indexedClass.getInnerClassType() == InnerClassType.MEMBER;
    }
}
