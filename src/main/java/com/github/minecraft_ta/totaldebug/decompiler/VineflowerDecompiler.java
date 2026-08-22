package com.github.minecraft_ta.totaldebug.decompiler;

import com.github.minecraft_ta.totaldebug.bytecode.ClassBytecodeSource;
import com.github.minecraft_ta.totaldebug.decompiler.naming.SelectiveVariableNamingPlugin;
import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.api.plugin.PluginSource;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.jetbrains.java.decompiler.main.plugins.PluginSources;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.Manifest;

public final class VineflowerDecompiler implements JavaDecompiler {
    private static final String PARTIAL_OUTPUT_MARKER = "$VF: Couldn't be decompiled";
    private static final PluginSource NAMING_PLUGIN_SOURCE =
            () -> List.of(new SelectiveVariableNamingPlugin());

    static {
        synchronized (PluginSources.PLUGIN_SOURCES) {
            PluginSources.PLUGIN_SOURCES.add(NAMING_PLUGIN_SOURCE);
        }
    }

    @Override
    public DecompilationResult decompile(String binaryName, ClassBytecodeSource bytecodeSource)
            throws DecompilationException {
        Objects.requireNonNull(binaryName, "binaryName");
        Objects.requireNonNull(bytecodeSource, "bytecodeSource");
        if (binaryName.isBlank()) {
            throw new IllegalArgumentException("binaryName must not be blank");
        }
        if (binaryName.indexOf('/') >= 0 || binaryName.endsWith(".class")) {
            throw new IllegalArgumentException("binaryName must use Java binary-name syntax: " + binaryName);
        }

        byte[] targetBytes = readTargetBytes(binaryName, bytecodeSource);
        String internalName = binaryName.replace('.', '/');
        Map<String, byte[]> targetClasses = readTargetClasses(internalName, targetBytes, bytecodeSource);
        InMemoryResultSaver resultSaver = new InMemoryResultSaver();
        DiagnosticLogger logger = new DiagnosticLogger();

        try {
            Decompiler.builder()
                    .inputs(new TargetClassContext(internalName, targetClasses, bytecodeSource))
                    .libraries(new BytecodeLookupContext(bytecodeSource))
                    .output(resultSaver)
                    .logger(logger)
                    .option(IFernflowerPreferences.THREADS, "1")
                    .option(IFernflowerPreferences.INCLUDE_JAVA_RUNTIME, "current")
                    .option(IFernflowerPreferences.DECOMPILER_COMMENTS, true)
                    .option(IFernflowerPreferences.DUMP_EXCEPTION_ON_ERROR, true)
                    .build()
                    .decompile();
        } catch (RuntimeException failure) {
            throw new DecompilationException("Vineflower failed to decompile " + binaryName, failure);
        }

        String source = resultSaver.sourceFor(binaryName);
        if (source == null || source.isBlank()) {
            throw new DecompilationException(missingOutputMessage(binaryName, logger.diagnostics()));
        }

        List<DecompilerDiagnostic> diagnostics = logger.diagnostics();
        boolean hasErrors = diagnostics.stream()
                .anyMatch(diagnostic -> diagnostic.severity() == DecompilerDiagnostic.Severity.ERROR);
        DecompilationResult.Status status = hasErrors || source.contains(PARTIAL_OUTPUT_MARKER)
                ? DecompilationResult.Status.PARTIAL
                : DecompilationResult.Status.COMPLETE;
        return new DecompilationResult(source, status, diagnostics);
    }

    private static byte[] readTargetBytes(String binaryName, ClassBytecodeSource bytecodeSource)
            throws DecompilationException {
        byte[] bytes;
        try {
            bytes = bytecodeSource.findClassBytes(binaryName);
        } catch (IOException failure) {
            throw new DecompilationException("Unable to read bytecode for " + binaryName, failure);
        }
        if (bytes == null || bytes.length == 0) {
            throw new DecompilationException("No bytecode is available for " + binaryName);
        }
        return bytes;
    }

    private static Map<String, byte[]> readTargetClasses(
            String targetInternalName,
            byte[] targetBytes,
            ClassBytecodeSource bytecodeSource
    ) throws DecompilationException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(targetInternalName, targetBytes);
        Deque<String> pending = new ArrayDeque<>();
        pending.add(targetInternalName);

        while (!pending.isEmpty()) {
            String owner = pending.removeFirst();
            byte[] ownerBytes = classes.get(owner);
            List<String> nestedClasses = new ArrayList<>();
            try {
                new ClassReader(ownerBytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInnerClass(String name, String outerName, String innerName, int access) {
                        if (name.startsWith(targetInternalName + '$') && !classes.containsKey(name)) {
                            nestedClasses.add(name);
                        }
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (IllegalArgumentException failure) {
                throw new DecompilationException("Unable to inspect nested classes of " + owner.replace('/', '.'), failure);
            }

            for (String nestedClass : nestedClasses) {
                if (classes.containsKey(nestedClass)) {
                    continue;
                }
                byte[] nestedBytes;
                try {
                    nestedBytes = bytecodeSource.findClassBytes(nestedClass);
                } catch (IOException failure) {
                    throw new DecompilationException(
                            "Unable to read nested class bytecode for " + nestedClass.replace('/', '.'),
                            failure
                    );
                }
                if (nestedBytes == null || nestedBytes.length == 0) {
                    throw new DecompilationException(
                            "No bytecode is available for nested class " + nestedClass.replace('/', '.')
                    );
                }
                classes.put(nestedClass, nestedBytes);
                pending.addLast(nestedClass);
            }
        }

        return Collections.unmodifiableMap(new LinkedHashMap<>(classes));
    }

    private static String missingOutputMessage(String binaryName, List<DecompilerDiagnostic> diagnostics) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity() == DecompilerDiagnostic.Severity.ERROR)
                .findFirst()
                .map(diagnostic -> "Vineflower produced no source for " + binaryName + ": " + diagnostic.message())
                .orElse("Vineflower produced no source for " + binaryName);
    }

    private static final class TargetClassContext implements IContextSource {
        private final String internalName;
        private final Map<String, byte[]> targetClasses;
        private final ClassBytecodeSource bytecodeSource;

        private TargetClassContext(
                String internalName,
                Map<String, byte[]> targetClasses,
                ClassBytecodeSource bytecodeSource
        ) {
            this.internalName = internalName;
            this.targetClasses = targetClasses;
            this.bytecodeSource = bytecodeSource;
        }

        @Override
        public String getName() {
            return this.internalName;
        }

        @Override
        public Entries getEntries() {
            List<Entry> entries = this.targetClasses.keySet().stream()
                    .map(Entry::atBase)
                    .toList();
            return new Entries(entries, List.of(), List.of());
        }

        @Override
        public InputStream getInputStream(String resource) throws IOException {
            String className = resource.endsWith(CLASS_SUFFIX)
                    ? resource.substring(0, resource.length() - CLASS_SUFFIX.length())
                    : resource;
            byte[] targetBytes = this.targetClasses.get(className);
            if (targetBytes != null) {
                return new ByteArrayInputStream(targetBytes);
            }
            return streamFor(this.bytecodeSource, resource);
        }

        @Override
        public IOutputSink createOutputSink(IResultSaver saver) {
            return new IOutputSink() {
                @Override
                public void begin() {
                }

                @Override
                public void acceptClass(String qualifiedName, String fileName, String content, int[] mapping) {
                    String entryName = fileName.substring(fileName.lastIndexOf('/') + 1);
                    saver.saveClassFile("", qualifiedName, entryName, content, mapping);
                }

                @Override
                public void acceptDirectory(String directory) {
                }

                @Override
                public void acceptOther(String path) {
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static final class BytecodeLookupContext implements IContextSource {
        private final ClassBytecodeSource bytecodeSource;

        private BytecodeLookupContext(ClassBytecodeSource bytecodeSource) {
            this.bytecodeSource = bytecodeSource;
        }

        @Override
        public String getName() {
            return "target defining class loader";
        }

        @Override
        public Entries getEntries() {
            return Entries.EMPTY;
        }

        @Override
        public boolean isLazy() {
            return true;
        }

        @Override
        public boolean hasClass(String className) throws IOException {
            return this.bytecodeSource.findClassBytes(className) != null;
        }

        @Override
        public byte[] getClassBytes(String className) throws IOException {
            return this.bytecodeSource.findClassBytes(className);
        }

        @Override
        public InputStream getInputStream(String resource) throws IOException {
            return streamFor(this.bytecodeSource, resource);
        }
    }

    private static InputStream streamFor(ClassBytecodeSource bytecodeSource, String resource) throws IOException {
        byte[] bytes = bytecodeSource.findClassBytes(resource);
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    private static final class InMemoryResultSaver implements IResultSaver {
        private final Map<String, String> sources = new LinkedHashMap<>();

        private String sourceFor(String binaryName) {
            return this.sources.get(binaryName);
        }

        private void save(String qualifiedName, String content) {
            String binaryName = qualifiedName.replace('/', '.');
            String previous = this.sources.putIfAbsent(binaryName, content);
            if (previous != null && !previous.equals(content)) {
                throw new IllegalStateException("Vineflower emitted multiple sources for " + binaryName);
            }
        }

        @Override
        public void saveFolder(String path) {
        }

        @Override
        public void copyFile(String source, String path, String entryName) {
        }

        @Override
        public void saveClassFile(
                String path,
                String qualifiedName,
                String entryName,
                String content,
                int[] mapping
        ) {
            save(qualifiedName, content);
        }

        @Override
        public void createArchive(String path, String archiveName, Manifest manifest) {
        }

        @Override
        public void saveDirEntry(String path, String archiveName, String entryName) {
        }

        @Override
        public void copyEntry(String source, String path, String archiveName, String entry) {
        }

        @Override
        public void saveClassEntry(
                String path,
                String archiveName,
                String qualifiedName,
                String entryName,
                String content
        ) {
            save(qualifiedName, content);
        }

        @Override
        public void closeArchive(String path, String archiveName) {
        }
    }

    private static final class DiagnosticLogger extends IFernflowerLogger {
        private final List<DecompilerDiagnostic> diagnostics = new ArrayList<>();

        private DiagnosticLogger() {
            setSeverity(Severity.WARN);
        }

        @Override
        public void writeMessage(String message, Severity severity) {
            add(message, severity, null);
        }

        @Override
        public void writeMessage(String message, Severity severity, Throwable failure) {
            add(message, severity, failure);
        }

        private void add(String message, Severity severity, Throwable failure) {
            DecompilerDiagnostic.Severity diagnosticSeverity = switch (severity) {
                case WARN -> DecompilerDiagnostic.Severity.WARNING;
                case ERROR -> DecompilerDiagnostic.Severity.ERROR;
                case TRACE, INFO -> null;
            };
            if (diagnosticSeverity == null) {
                return;
            }

            this.diagnostics.add(new DecompilerDiagnostic(
                    diagnosticSeverity,
                    message,
                    failure == null ? null : failure.getClass().getName(),
                    failure == null ? null : failure.getMessage()
            ));
        }

        private List<DecompilerDiagnostic> diagnostics() {
            return List.copyOf(this.diagnostics);
        }
    }
}
