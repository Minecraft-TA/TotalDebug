package com.github.minecraft_ta.totalDebugCompanion.jdt.impls;

import com.github.minecraft_ta.totalDebugCompanion.jdt.stubs.IJavaElementRequestorStub;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.internal.codeassist.ISearchRequestor;
import org.eclipse.jdt.internal.compiler.ExtraFlags;
import org.eclipse.jdt.internal.core.JavaProject;
import org.eclipse.jdt.internal.core.SearchableEnvironment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

class SearchableEnvironmentImpl extends SearchableEnvironment {

    private static final Method findExactTypes;
    private static final Method findTypes;
    private static final Method convertSearchFilterToModelFilter;
    static {
        try {
            findExactTypes = SearchableEnvironment.class.getDeclaredMethod("findExactTypes", String.class, ISearchRequestor.class, int.class);
            findExactTypes.setAccessible(true);
            findTypes = SearchableEnvironment.class.getDeclaredMethod("findTypes", String.class, ISearchRequestor.class, int.class);
            findTypes.setAccessible(true);
            convertSearchFilterToModelFilter = SearchableEnvironment.class.getDeclaredMethod("convertSearchFilterToModelFilter", int.class);
            convertSearchFilterToModelFilter.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public SearchableEnvironmentImpl(JavaProjectImpl javaProject) throws JavaModelException {
        super(javaProject, (WorkingCopyOwner) null, true, JavaProject.NO_RELEASE);
    }

    @Override
    public void findTypes(char[] prefix, boolean findMembers, int matchRule, int searchFor, boolean resolveDocumentName, ISearchRequestor storage, IProgressMonitor monitor) {
        try {
            findTypes.invoke(this, new String(prefix), storage, (int) convertSearchFilterToModelFilter.invoke(this, searchFor));
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    @Override
    public void findExactTypes(char[] name, boolean findMembers, int searchFor, ISearchRequestor storage) {
        try {
            findExactTypes.invoke(this, new String(name), storage, (int) convertSearchFilterToModelFilter.invoke(this, searchFor));
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void findConstructorDeclarations(char[] prefix, int matchRule, boolean resolveDocumentName, ISearchRequestor storage, IProgressMonitor monitor) {
        if (resolveDocumentName)
            throw new UnsupportedOperationException();

        var matchSubstring = (SearchPattern.R_SUBSTRING_MATCH & matchRule) != 0;

        var types = new ArrayList<IType>();
        this.nameLookup.seekTypes(new String(prefix), null, matchSubstring, 0, new IJavaElementRequestorStub() {
            @Override
            public void acceptType(IType type) {
                types.add(type);
            }
        }, true);

        try {
            for (IType type : types) {
                for (IMethod method : type.getMethods()) {
                    if (!method.isConstructor())
                        continue;

                    String[] stringParameterTypes = method.getParameterTypes();
                    int length = stringParameterTypes.length;
                    char[][] parameterTypes = new char[length][];
                    for (int l = 0; l < length; l++)
                        parameterTypes[l] = Signature.toCharArray(Signature.getTypeErasure(stringParameterTypes[l]).toCharArray());

                    var simpleName = Signature.getSimpleName(type.getFullyQualifiedName());
                    storage.acceptConstructor(
                            method.getFlags(), simpleName.toCharArray(),
                            method.getNumberOfParameters(), method.getSignature().toCharArray(),
                            parameterTypes,
                            null,
                            type.getFlags(),
                            type.getPackageFragment().getElementName().toCharArray(),
                            ExtraFlags.getExtraFlags(type),
                            null,
                            null
                    );
                }
            }
        } catch (JavaModelException e) {
            throw new RuntimeException(e);
        } finally {
            monitor.done();
        }
    }
}
