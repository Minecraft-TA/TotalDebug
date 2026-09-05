package com.github.minecraft_ta.totalDebugCompanion.jdt.stubs;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.jobs.ISchedulingRule;

import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.util.Map;

public interface IFileStub extends IFile {

    @Override
    default void appendContents(InputStream source, boolean force, boolean keepHistory, IProgressMonitor monitor) {

    }

    @Override
    default void appendContents(InputStream source, int updateFlags, IProgressMonitor monitor) {

    }

    @Override
    default void create(InputStream source, boolean force, IProgressMonitor monitor) {

    }

    @Override
    default void create(InputStream source, int updateFlags, IProgressMonitor monitor) {

    }

    @Override
    default void createLink(IPath localLocation, int updateFlags, IProgressMonitor monitor) {

    }

    @Override
    default void createLink(URI location, int updateFlags, IProgressMonitor monitor) {

    }

    @Override
    default void delete(boolean force, boolean keepHistory, IProgressMonitor monitor) {

    }

    @Override
    default String getCharset() {
        return null;
    }

    @Override
    default String getCharset(boolean checkImplicit) {
        return null;
    }

    @Override
    default String getCharsetFor(Reader reader) {
        return null;
    }

    @Override
    default IContentDescription getContentDescription() {
        return null;
    }

    @Override
    default InputStream getContents() {
        return null;
    }

    @Override
    default InputStream getContents(boolean force) {
        return null;
    }

    @Override
    default int getEncoding() {
        return 0;
    }

    @Override
    default IPath getFullPath() {
        return null;
    }

    @Override
    default IFileState[] getHistory(IProgressMonitor monitor) {
        return new IFileState[0];
    }

    @Override
    default String getName() {
        return null;
    }

    @Override
    default boolean isReadOnly() {
        return false;
    }

    @Override
    default void move(IPath destination, boolean force, boolean keepHistory, IProgressMonitor monitor) {

    }

    @Override
    default void setCharset(String newCharset) {

    }

    @Override
    default void setCharset(String newCharset, IProgressMonitor monitor) {

    }

    @Override
    default void setContents(InputStream source, boolean force, boolean keepHistory, IProgressMonitor monitor) {

    }

    @Override
    default void setContents(IFileState source, boolean force, boolean keepHistory, IProgressMonitor monitor) {

    }

    @Override
    default void setContents(InputStream source, int updateFlags, IProgressMonitor monitor) {

    }

    @Override
    default void setContents(IFileState source, int updateFlags, IProgressMonitor monitor) {

    }

    @Override
    default void accept(IResourceProxyVisitor visitor, int memberFlags) {

    }

    @Override
    default void accept(IResourceProxyVisitor visitor, int depth, int memberFlags) {

    }

    @Override
    default void accept(IResourceVisitor visitor) {

    }

    @Override
    default void accept(IResourceVisitor visitor, int depth, boolean includePhantoms) {

    }

    @Override
    default void accept(IResourceVisitor visitor, int depth, int memberFlags) {

    }

    @Override
    default void clearHistory(IProgressMonitor monitor) {

    }

    @Override
    default void copy(IPath destination, boolean force, IProgressMonitor monitor) {

    }

    @Override
    default void copy(IPath destination, int updateFlags, IProgressMonitor monitor) {

    }

    @Override
    default void copy(IProjectDescription description, boolean force, IProgressMonitor monitor) {

    }

    @Override
    default void copy(IProjectDescription description, int updateFlags, IProgressMonitor monitor) {

    }

    @Override
    default IMarker createMarker(String type) {
        return null;
    }

    @Override
    default IResourceProxy createProxy() {
        return null;
    }

    @Override
    default void delete(boolean force, IProgressMonitor monitor) {

    }

    @Override
    default void delete(int updateFlags, IProgressMonitor monitor) {

    }

    @Override
    default void deleteMarkers(String type, boolean includeSubtypes, int depth) {

    }

    @Override
    default boolean exists() {
        return false;
    }

    @Override
    default IMarker findMarker(long id) {
        return null;
    }

    @Override
    default IMarker[] findMarkers(String type, boolean includeSubtypes, int depth) {
        return new IMarker[0];
    }

    @Override
    default int findMaxProblemSeverity(String type, boolean includeSubtypes, int depth) {
        return 0;
    }

    @Override
    default String getFileExtension() {
        return null;
    }

    @Override
    default long getLocalTimeStamp() {
        return 0;
    }

    @Override
    default IPath getLocation() {
        return null;
    }

    @Override
    default URI getLocationURI() {
        return null;
    }

    @Override
    default IMarker getMarker(long id) {
        return null;
    }

    @Override
    default long getModificationStamp() {
        return 0;
    }

    @Override
    default IPathVariableManager getPathVariableManager() {
        return null;
    }

    @Override
    default IContainer getParent() {
        return null;
    }

    @Override
    default Map<QualifiedName, String> getPersistentProperties() {
        return null;
    }

    @Override
    default String getPersistentProperty(QualifiedName key) {
        return null;
    }

    @Override
    default IProject getProject() {
        return null;
    }

    @Override
    default IPath getProjectRelativePath() {
        return null;
    }

    @Override
    default IPath getRawLocation() {
        return null;
    }

    @Override
    default URI getRawLocationURI() {
        return null;
    }

    @Override
    default ResourceAttributes getResourceAttributes() {
        return null;
    }

    @Override
    default Map<QualifiedName, Object> getSessionProperties() {
        return null;
    }

    @Override
    default Object getSessionProperty(QualifiedName key) {
        return null;
    }

    @Override
    default int getType() {
        return 0;
    }

    @Override
    default IWorkspace getWorkspace() {
        return null;
    }

    @Override
    default boolean isAccessible() {
        return false;
    }

    @Override
    default boolean isDerived() {
        return false;
    }

    @Override
    default boolean isDerived(int options) {
        return false;
    }

    @Override
    default boolean isHidden() {
        return false;
    }

    @Override
    default boolean isHidden(int options) {
        return false;
    }

    @Override
    default boolean isLinked() {
        return false;
    }

    @Override
    default boolean isVirtual() {
        return false;
    }

    @Override
    default boolean isLinked(int options) {
        return false;
    }

    @Override
    default boolean isLocal(int depth) {
        return false;
    }

    @Override
    default boolean isPhantom() {
        return false;
    }

    @Override
    default boolean isSynchronized(int depth) {
        return false;
    }

    @Override
    default boolean isTeamPrivateMember() {
        return false;
    }

    @Override
    default boolean isTeamPrivateMember(int options) {
        return false;
    }

    @Override
    default void move(IPath destination, boolean force, IProgressMonitor monitor) {

    }

    @Override
    default void move(IPath destination, int updateFlags, IProgressMonitor monitor) {

    }

    @Override
    default void move(IProjectDescription description, boolean force, boolean keepHistory, IProgressMonitor monitor) {

    }

    @Override
    default void move(IProjectDescription description, int updateFlags, IProgressMonitor monitor) {

    }

    @Override
    default void refreshLocal(int depth, IProgressMonitor monitor) {

    }

    @Override
    default void revertModificationStamp(long value) {

    }

    @Override
    default void setDerived(boolean isDerived) {

    }

    @Override
    default void setDerived(boolean isDerived, IProgressMonitor monitor) {

    }

    @Override
    default void setHidden(boolean isHidden) {

    }

    @Override
    default void setLocal(boolean flag, int depth, IProgressMonitor monitor) {

    }

    @Override
    default long setLocalTimeStamp(long value) {
        return 0;
    }

    @Override
    default void setPersistentProperty(QualifiedName key, String value) {

    }

    @Override
    default void setReadOnly(boolean readOnly) {

    }

    @Override
    default void setResourceAttributes(ResourceAttributes attributes) {

    }

    @Override
    default void setSessionProperty(QualifiedName key, Object value) {

    }

    @Override
    default void setTeamPrivateMember(boolean isTeamPrivate) {

    }

    @Override
    default void touch(IProgressMonitor monitor) {

    }

    @Override
    default <T> T getAdapter(Class<T> adapter) {
        return null;
    }

    @Override
    default boolean contains(ISchedulingRule rule) {
        return false;
    }

    @Override
    default boolean isConflicting(ISchedulingRule rule) {
        return false;
    }
}
