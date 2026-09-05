package com.github.minecraft_ta.totalDebugCompanion.jdt.stubs;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.jdt.core.*;

public interface IMethodStub extends IMethod {

    @Override
    default IMemberValuePair getDefaultValue() {
        return null;
    }

    @Override
    default String getElementName() {
        return null;
    }

    @Override
    default String[] getExceptionTypes() {
        return new String[0];
    }

    @Override
    default String[] getTypeParameterSignatures() {
        return new String[0];
    }

    @Override
    default ITypeParameter[] getTypeParameters() {
        return new ITypeParameter[0];
    }

    @Override
    default int getNumberOfParameters() {
        return 0;
    }

    @Override
    default ILocalVariable[] getParameters() {
        return new ILocalVariable[0];
    }

    @Override
    default String getKey() {
        return null;
    }

    @Override
    default String[] getParameterNames() {
        return new String[0];
    }

    @Override
    default String[] getParameterTypes() {
        return new String[0];
    }

    @Override
    default String[] getRawParameterNames() {
        return new String[0];
    }

    @Override
    default String getReturnType() {
        return null;
    }

    @Override
    default String getSignature() {
        return null;
    }

    @Override
    default ITypeParameter getTypeParameter(String name) {
        return null;
    }

    @Override
    default boolean isConstructor() {
        return false;
    }

    @Override
    default boolean isMainMethod() {
        return false;
    }

    @Override
    default boolean isMainMethodCandidate() {
        return false;
    }

    @Override
    default boolean isLambdaMethod() {
        return false;
    }

    @Override
    default boolean isResolved() {
        return false;
    }

    @Override
    default boolean isSimilar(IMethod method) {
        return false;
    }

    @Override
    default IAnnotation getAnnotation(String name) {
        return null;
    }

    @Override
    default IAnnotation[] getAnnotations() {
        return new IAnnotation[0];
    }

    @Override
    default String[] getCategories() {
        return new String[0];
    }

    @Override
    default IClassFile getClassFile() {
        return null;
    }

    @Override
    default ICompilationUnit getCompilationUnit() {
        return null;
    }

    @Override
    default IType getDeclaringType() {
        return null;
    }

    @Override
    default int getFlags() {
        return 0;
    }

    @Override
    default ISourceRange getJavadocRange() {
        return null;
    }

    @Override
    default int getOccurrenceCount() {
        return 0;
    }

    @Override
    default ITypeRoot getTypeRoot() {
        return null;
    }

    @Override
    default IType getType(String name, int occurrenceCount) {
        return null;
    }

    @Override
    default boolean isBinary() {
        return false;
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
    default String getAttachedJavadoc(IProgressMonitor monitor) {
        return null;
    }

    @Override
    default IResource getCorrespondingResource() {
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
    default IResource getUnderlyingResource() {
        return null;
    }

    @Override
    default boolean isReadOnly() {
        return false;
    }

    @Override
    default boolean isStructureKnown() {
        return false;
    }

    @Override
    default <T> T getAdapter(Class<T> adapter) {
        return null;
    }

    @Override
    default IJavaElement[] getChildren() {
        return new IJavaElement[0];
    }

    @Override
    default boolean hasChildren() {
        return false;
    }

    @Override
    default void copy(IJavaElement container, IJavaElement sibling, String rename, boolean replace, IProgressMonitor monitor) {

    }

    @Override
    default void delete(boolean force, IProgressMonitor monitor) {

    }

    @Override
    default void move(IJavaElement container, IJavaElement sibling, String rename, boolean replace, IProgressMonitor monitor) {

    }

    @Override
    default void rename(String name, boolean replace, IProgressMonitor monitor) {

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
