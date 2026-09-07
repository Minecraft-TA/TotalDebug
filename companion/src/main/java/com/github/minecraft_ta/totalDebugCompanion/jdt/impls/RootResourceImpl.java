package com.github.minecraft_ta.totalDebugCompanion.jdt.impls;

import com.github.minecraft_ta.totalDebugCompanion.jdt.stubs.IContainerStub;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

public class RootResourceImpl implements IContainerStub {

    @Override
    public IPath getFullPath() {
        return new Path("dummy-folder");
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public boolean isAccessible() {
        return true;
    }
}
