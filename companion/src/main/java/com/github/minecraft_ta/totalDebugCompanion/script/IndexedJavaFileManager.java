package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource.Source;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexedClass;
import com.github.tth05.jindex.IndexedPackage;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/** Borrows Companion's index and opens only the archives javac actually reads. */
final class IndexedJavaFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
    private final ClassIndex index;
    private final Map<Integer, Path> sources = new HashMap<>();
    private final Map<Integer, JarFile> archives = new HashMap<>();

    IndexedJavaFileManager(StandardJavaFileManager standard, ClassIndex index, List<Source> sources) {
        super(standard);
        this.index = index;
        for (Source source : sources) {
            // --release supplies the platform classes from javac's own standard manager.
            if (!"jrt:/".equals(source.logicalUri())) this.sources.put(source.sourceId(), source.path());
        }
    }

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName,
                                         Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
        if (location != StandardLocation.CLASS_PATH) return super.list(location, packageName, kinds, recurse);
        if (!kinds.contains(JavaFileObject.Kind.CLASS)) return List.of();
        var result = new ArrayList<JavaFileObject>();
        listPackage(this.index.findPackage(packageName), recurse, result);
        return result;
    }

    private void listPackage(IndexedPackage item, boolean recurse, List<JavaFileObject> output) {
        if (item == null) return;
        for (IndexedClass type : item.getClasses()) {
            JavaFileObject input = input(type);
            if (input != null) output.add(input);
        }
        if (recurse) for (IndexedPackage child : item.getSubPackages()) listPackage(child, true, output);
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String name, JavaFileObject.Kind kind)
            throws IOException {
        if (location != StandardLocation.CLASS_PATH) return super.getJavaFileForInput(location, name, kind);
        return kind == JavaFileObject.Kind.CLASS ? input(this.index.findClass(name)) : null;
    }

    private JavaFileObject input(IndexedClass type) {
        if (type == null) return null;
        Path path = this.sources.get(type.getSourceId());
        return path == null ? null : new IndexedInput(type.getNameWithPackageDot(), type.getSourceId(), path);
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        return file instanceof IndexedInput input ? input.name : super.inferBinaryName(location, file);
    }

    @Override
    public boolean isSameFile(FileObject first, FileObject second) {
        if (first instanceof IndexedInput || second instanceof IndexedInput) return first.toUri().equals(second.toUri());
        return super.isSameFile(first, second);
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (JarFile archive : this.archives.values()) {
            try { archive.close(); }
            catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        this.archives.clear();
        try { super.close(); }
        catch (IOException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        if (failure != null) throw failure;
    }

    private final class IndexedInput extends SimpleJavaFileObject {
        private final String name;
        private final int sourceId;
        private final Path path;

        private IndexedInput(String name, int sourceId, Path path) {
            super(URI.create("jindex:///" + sourceId + "/" + name.replace('.', '/') + ".class"), Kind.CLASS);
            this.name = name;
            this.sourceId = sourceId;
            this.path = path;
        }

        @Override
        public InputStream openInputStream() throws IOException {
            String resource = this.name.replace('.', '/') + ".class";
            if (Files.isDirectory(this.path)) return Files.newInputStream(this.path.resolve(resource));
            JarFile archive = archives.get(this.sourceId);
            if (archive == null) {
                archive = new JarFile(this.path.toFile(), false, ZipFile.OPEN_READ, Runtime.Version.parse("21"));
                archives.put(this.sourceId, archive);
            }
            var entry = archive.getJarEntry(resource);
            if (entry == null) throw new IOException("Runtime index points to a missing class: " + this.path + " / " + resource);
            return archive.getInputStream(entry);
        }
    }
}
