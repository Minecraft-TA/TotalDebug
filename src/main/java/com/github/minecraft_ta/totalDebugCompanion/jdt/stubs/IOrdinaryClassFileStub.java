package com.github.minecraft_ta.totalDebugCompanion.jdt.stubs;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.jdt.core.*;

public interface IOrdinaryClassFileStub extends IOrdinaryClassFile {

    @Override
    default IType getType() {
        return null;
    }

    @Override
    default ICompilationUnit becomeWorkingCopy(IProblemRequestor problemRequestor, WorkingCopyOwner owner, IProgressMonitor monitor) {
        return null;
    }

    @Override
    default byte[] getBytes() {
        return new byte[0];
    }

    @Override
    default IJavaElement getWorkingCopy(IProgressMonitor monitor, IBufferFactory factory) {
        return null;
    }

    @Override
    default boolean isClass() {
        return false;
    }

    @Override
    default boolean isInterface() {
        return false;
    }

    @Override
    default IType findPrimaryType() {
        return null;
    }

    @Override
    default IJavaElement getElementAt(int position) {
        return null;
    }

    @Override
    default ICompilationUnit getWorkingCopy(WorkingCopyOwner owner, IProgressMonitor monitor) {
        return null;
    }

    @Override
    default void codeComplete(int offset, ICodeCompletionRequestor requestor) {

    }

    @Override
    default void codeComplete(int offset, ICompletionRequestor requestor) {

    }

    @Override
    default void codeComplete(int offset, CompletionRequestor requestor) {

    }

    @Override
    default void codeComplete(int offset, CompletionRequestor requestor, IProgressMonitor monitor) {

    }

    @Override
    default void codeComplete(int offset, ICompletionRequestor requestor, WorkingCopyOwner owner) {

    }

    @Override
    default void codeComplete(int offset, CompletionRequestor requestor, WorkingCopyOwner owner) {

    }

    @Override
    default void codeComplete(int offset, CompletionRequestor requestor, WorkingCopyOwner owner, IProgressMonitor monitor) {

    }

    @Override
    default IJavaElement[] codeSelect(int offset, int length) {
        return new IJavaElement[0];
    }

    @Override
    default IJavaElement[] codeSelect(int offset, int length, WorkingCopyOwner owner) {
        return new IJavaElement[0];
    }

    @Override
    default boolean exists() {
        return false;
    }

    @Override
    default IJavaElement getAncestor(int ancestorType) {
        return null;
    }

    @Override
    default String getAttachedJavadoc(IProgressMonitor monitor) throws JavaModelException {
        return null;
    }

    @Override
    default IResource getCorrespondingResource() throws JavaModelException {
        return null;
    }

    @Override
    default String getElementName() {
        return null;
    }

    @Override
    default int getElementType() {
        return 0;
    }

    @Override
    default String getHandleIdentifier() {
        return null;
    }

    @Override
    default IJavaModel getJavaModel() {
        return null;
    }

    @Override
    default IJavaProject getJavaProject() {
        return null;
    }

    @Override
    default IOpenable getOpenable() {
        return null;
    }

    @Override
    default IJavaElement getParent() {
        return null;
    }

    @Override
    default IPath getPath() {
        return null;
    }

    @Override
    default IJavaElement getPrimaryElement() {
        return null;
    }

    @Override
    default IResource getResource() {
        return null;
    }

    @Override
    default ISchedulingRule getSchedulingRule() {
        return null;
    }

    @Override
    default IResource getUnderlyingResource() throws JavaModelException {
        return null;
    }

    @Override
    default boolean isReadOnly() {
        return false;
    }

    @Override
    default boolean isStructureKnown() throws JavaModelException {
        return false;
    }

    @Override
    default <T> T getAdapter(Class<T> adapter) {
        return null;
    }

    @Override
    default void close() throws JavaModelException {

    }

    @Override
    default String findRecommendedLineSeparator() throws JavaModelException {
        return null;
    }

    @Override
    default IBuffer getBuffer() throws JavaModelException {
        return null;
    }

    @Override
    default boolean hasUnsavedChanges() throws JavaModelException {
        return false;
    }

    @Override
    default boolean isConsistent() throws JavaModelException {
        return false;
    }

    @Override
    default boolean isOpen() {
        return false;
    }

    @Override
    default void makeConsistent(IProgressMonitor progress) throws JavaModelException {

    }

    @Override
    default void open(IProgressMonitor progress) throws JavaModelException {

    }

    @Override
    default void save(IProgressMonitor progress, boolean force) throws JavaModelException {

    }

    @Override
    default IJavaElement[] getChildren() throws JavaModelException {
        return new IJavaElement[0];
    }

    @Override
    default boolean hasChildren() throws JavaModelException {
        return false;
    }

    @Override
    default String getSource() {
        return null;
    }

    @Override
    default ISourceRange getSourceRange() {
        return null;
    }

    @Override
    default ISourceRange getNameRange() {
        return null;
    }
}
