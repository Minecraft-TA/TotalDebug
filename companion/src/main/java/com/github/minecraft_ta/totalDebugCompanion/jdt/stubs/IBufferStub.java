package com.github.minecraft_ta.totalDebugCompanion.jdt.stubs;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.IBufferChangedListener;
import org.eclipse.jdt.core.IOpenable;
import org.eclipse.jdt.core.JavaModelException;

public interface IBufferStub extends IBuffer {

    @Override
    default void addBufferChangedListener(IBufferChangedListener listener) {

    }

    @Override
    default void append(char[] text) {

    }

    @Override
    default void append(String text) {

    }

    @Override
    default void close() {

    }

    @Override
    default char getChar(int position) {
        return 0;
    }

    @Override
    default char[] getCharacters() {
        return new char[0];
    }

    @Override
    default String getContents() {
        return null;
    }

    @Override
    default int getLength() {
        return 0;
    }

    @Override
    default IOpenable getOwner() {
        return null;
    }

    @Override
    default String getText(int offset, int length) throws IndexOutOfBoundsException {
        return null;
    }

    @Override
    default IResource getUnderlyingResource() {
        return null;
    }

    @Override
    default boolean hasUnsavedChanges() {
        return false;
    }

    @Override
    default boolean isClosed() {
        return false;
    }

    @Override
    default boolean isReadOnly() {
        return false;
    }

    @Override
    default void removeBufferChangedListener(IBufferChangedListener listener) {

    }

    @Override
    default void replace(int position, int length, char[] text) {

    }

    @Override
    default void replace(int position, int length, String text) {

    }

    @Override
    default void save(IProgressMonitor progress, boolean force) {

    }

    @Override
    default void setContents(char[] contents) {

    }

    @Override
    default void setContents(String contents) {

    }
}
