package com.github.minecraft_ta.totalDebugCompanion.jdt.impls;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JDTHacks;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JIndexResolvedBinaryType;
import com.github.minecraft_ta.totalDebugCompanion.jdt.stubs.IOrdinaryClassFileStub;
import com.github.tth05.jindex.IndexedClass;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.jdt.internal.core.*;
import org.eclipse.jdt.internal.core.util.MementoTokenizer;

import java.util.Map;

public class ClassFileImpl extends Openable implements IOrdinaryClassFileStub {

    private JIndexResolvedBinaryType type;
    private final IndexedClass indexedClass;
    private final OpenableElementInfo classFileInfo;

    public ClassFileImpl(IndexedClass indexedClass) {
        super(JDTHacks.createPackageFragment(indexedClass.getPackage().getNameWithParentsDot()));
        this.indexedClass = indexedClass;
        try {
            this.classFileInfo = (OpenableElementInfo) JDTHacks.createInstance(Class.forName("org.eclipse.jdt.internal.core.ClassFileInfo"), new Class[0]);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public void setType(JIndexResolvedBinaryType type) {
        if (this.type != null)
            throw new IllegalStateException("Class file type is already initialized");
        this.type = type;
    }

    @Override
    protected boolean buildStructure(OpenableElementInfo info, IProgressMonitor pm, Map newElements, IResource underlyingResource) {
        JIndexResolvedBinaryType type = getType();
        info.setChildren(new IJavaElement[]{type});
        newElements.put(type, type.getElementInfo());

        Object fakeClassFile = JDTHacks.createInstance(ClassFile.class, new Class[]{PackageFragment.class, String.class}, null, null);
        JDTHacks.setField(fakeClassFile, "binaryType", type);
        JDTHacks.invokeMethod(classFileInfo, "readBinaryChildren", new Class[]{ClassFile.class, Map.class, IBinaryType.class}, fakeClassFile, newElements, type.getElementInfo());
        return true;
    }

    @Override
    protected OpenableElementInfo createElementInfo() {
        return this.classFileInfo;
    }

    @Override
    public String getElementName() {
        return this.indexedClass.getName() + ".class";
    }

    @Override
    public int getElementType() {
        return IJavaElement.CLASS_FILE;
    }

    @Override
    public JIndexResolvedBinaryType getType() {
        if (this.type == null)
            throw new IllegalStateException("Class file type has not been initialized");
        return type;
    }

    @Override
    protected IResource resource(PackageFragmentRoot root) {
        return null;
    }

    @Override
    protected IStatus validateExistence(IResource underlyingResource) {
        return Status.OK_STATUS;
    }

    @Override
    public IJavaElement getHandleFromMemento(String token, MementoTokenizer memento, WorkingCopyOwner owner) {
        return null;
    }

    @Override
    protected char getHandleMementoDelimiter() {
        return 0;
    }
}
