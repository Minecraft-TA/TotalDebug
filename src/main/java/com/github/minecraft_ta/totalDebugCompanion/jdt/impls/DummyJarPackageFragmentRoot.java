package com.github.minecraft_ta.totalDebugCompanion.jdt.impls;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JDTHacks;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.internal.core.JarPackageFragmentRoot;
import org.eclipse.jdt.internal.core.OpenableElementInfo;

import java.util.List;
import java.util.Map;

public class DummyJarPackageFragmentRoot extends JarPackageFragmentRoot {

    public DummyJarPackageFragmentRoot() {
        super(new RootResourceImpl(), new Path("dummy-jar-path"), JDTHacks.DUMMY_JAVA_PROJECT, null);
    }

    @Override
    protected boolean computeChildren(OpenableElementInfo info, IResource underlyingResource) throws JavaModelException {
        try {
            var packageContentType = Class.forName("org.eclipse.jdt.internal.core.JarPackageFragmentRootInfo$PackageContent");
            var emptyPackage = JDTHacks.createInstance(packageContentType, new Class[0]);
            JDTHacks.setField(info, "rawPackageInfo", Map.of(List.of(), emptyPackage));
            JDTHacks.setField(info, "overriddenClasses", Map.of());
            info.setChildren(new IJavaElement[0]);
            return true;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("JDT package content type is unavailable", e);
        }
    }
}
