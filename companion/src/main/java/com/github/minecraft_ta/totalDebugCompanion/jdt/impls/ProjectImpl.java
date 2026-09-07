package com.github.minecraft_ta.totalDebugCompanion.jdt.impls;

import com.github.minecraft_ta.totalDebugCompanion.jdt.stubs.IProjectStub;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

public class ProjectImpl implements IProjectStub {

    private static final FileImpl CLASSPATH_FILE = new FileImpl(".classpath", new BufferImpl("""
            <?xml version="1.0" encoding="UTF-8"?>
            <classpath>
                <classpathentry kind="src" path="src"/>
                <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
                <classpathentry kind="output" path="bin"/>
            </classpath>
            """));

    @Override
    public IFile getFile(String name) {
        if (name.equals(".classpath")) {
            //TODO: Can this be avoided?
            return CLASSPATH_FILE;
        }

        return null;
    }

    @Override
    public boolean hasNature(String natureId) {
        return true;
    }

    @Override
    public IPath getFullPath() {
        return new Path("pathToDummyProject");
    }

    @Override
    public String getName() {
        return "dummy-folder";
    }

    @Override
    public int getType() {
        return IResource.PROJECT;
    }
}
