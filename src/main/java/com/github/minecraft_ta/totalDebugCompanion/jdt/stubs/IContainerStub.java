package com.github.minecraft_ta.totalDebugCompanion.jdt.stubs;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;

public interface IContainerStub extends IContainer, IResourceStub {

    @Override
    default boolean exists(IPath path) {
        return false;
    }

    @Override
    default IResource findMember(String path) {
        return null;
    }

    @Override
    default IResource findMember(String path, boolean includePhantoms) {
        return null;
    }

    @Override
    default IResource findMember(IPath path) {
        return null;
    }

    @Override
    default IResource findMember(IPath path, boolean includePhantoms) {
        return null;
    }

    @Override
    default String getDefaultCharset() {
        return null;
    }

    @Override
    default String getDefaultCharset(boolean checkImplicit) {
        return null;
    }

    @Override
    default IFile getFile(IPath path) {
        return null;
    }

    @Override
    default IFolder getFolder(IPath path) {
        return null;
    }

    @Override
    default IResource[] members() {
        return new IResource[0];
    }

    @Override
    default IResource[] members(boolean includePhantoms) {
        return new IResource[0];
    }

    @Override
    default IResource[] members(int memberFlags) {
        return new IResource[0];
    }

    @Override
    default IFile[] findDeletedMembersWithHistory(int depth, IProgressMonitor monitor) {
        return new IFile[0];
    }

    @Override
    default void setDefaultCharset(String charset) {

    }

    @Override
    default void setDefaultCharset(String charset, IProgressMonitor monitor) {

    }

    @Override
    default IResourceFilterDescription createFilter(int type, FileInfoMatcherDescription matcherDescription, int updateFlags, IProgressMonitor monitor) {
        return null;
    }

    @Override
    default IResourceFilterDescription[] getFilters() {
        return new IResourceFilterDescription[0];
    }
}
