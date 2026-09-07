package com.github.minecraft_ta.totalDebugCompanion.jdt.stubs;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IAccessRule;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;

public interface IClassPathEntryStub extends IClasspathEntry {

    @Override
    default IPath getExternalAnnotationPath(IProject project, boolean resolve) {
        return null;
    }

    @Override
    default boolean combineAccessRules() {
        return false;
    }

    @Override
    default IAccessRule[] getAccessRules() {
        return new IAccessRule[0];
    }

    @Override
    default int getContentKind() {
        return 0;
    }

    @Override
    default int getEntryKind() {
        return 0;
    }

    @Override
    default IPath[] getExclusionPatterns() {
        return new IPath[0];
    }

    @Override
    default IClasspathAttribute[] getExtraAttributes() {
        return new IClasspathAttribute[0];
    }

    @Override
    default IPath[] getInclusionPatterns() {
        return new IPath[0];
    }

    @Override
    default IPath getOutputLocation() {
        return null;
    }

    @Override
    default IPath getPath() {
        return null;
    }

    @Override
    default IPath getSourceAttachmentPath() {
        return null;
    }

    @Override
    default IPath getSourceAttachmentRootPath() {
        return null;
    }

    @Override
    default IClasspathEntry getReferencingEntry() {
        return null;
    }

    @Override
    default boolean isExported() {
        return false;
    }

    @Override
    default IClasspathEntry getResolvedEntry() {
        return null;
    }
}
