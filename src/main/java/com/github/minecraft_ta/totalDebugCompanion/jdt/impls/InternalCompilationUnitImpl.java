package com.github.minecraft_ta.totalDebugCompanion.jdt.impls;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JDTHacks;
import com.github.tth05.jindex.IndexedClass;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.compiler.env.IElementInfo;
import org.eclipse.jdt.internal.core.CompilationUnit;
import org.eclipse.jdt.internal.core.DefaultWorkingCopyOwner;

public class InternalCompilationUnitImpl extends CompilationUnit {

    private final IElementInfo elementInfo;

    public InternalCompilationUnitImpl(IElementInfo elementInfo, IndexedClass indexedClass) {
        super(JDTHacks.createPackageFragment(indexedClass.getPackage().getNameWithParentsDot()), indexedClass.getName(), DefaultWorkingCopyOwner.PRIMARY);
        this.elementInfo = elementInfo;
    }

    @Override
    public boolean isWorkingCopy() {
        return true;
    }

    @Override
    public boolean isPrimary() {
        return true;
    }

    @Override
    protected IStatus validateExistence(IResource underlyingResource) {
        return Status.OK_STATUS;
    }


    @Override
    public IElementInfo getElementInfo() throws JavaModelException {
        return this.elementInfo;
    }
}
