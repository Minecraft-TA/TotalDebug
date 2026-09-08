package com.github.minecraft_ta.totaldebug.evaluation;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Compiles independent source units while retaining archive readers until closed or the classpath changes. */
public final class InMemoryJavaCompiler implements Closeable {
    private JavaCompiler compiler;
    private StandardJavaFileManager standardFileManager;
    private JavaFileManager inputFileManager;
    private final Function<StandardJavaFileManager, JavaFileManager> fileManagerFactory;
    private DiagnosticCollector<JavaFileObject> currentDiagnostics;
    private String classpath;
    private List<SourceStamp> sources = List.of();
    private boolean closed;

    public InMemoryJavaCompiler() {
        this(manager -> manager);
    }

    /** The compiler owns the returned manager, including its standard delegate. */
    public InMemoryJavaCompiler(Function<StandardJavaFileManager, JavaFileManager> fileManagerFactory) {
        this.fileManagerFactory = Objects.requireNonNull(fileManagerFactory, "fileManagerFactory");
    }

    public synchronized Map<String, byte[]> compile(
            String sourceCode,
            String primaryBinaryName,
            String classpath
    ) throws InMemoryCompilationException {
        Objects.requireNonNull(sourceCode, "sourceCode");
        Objects.requireNonNull(primaryBinaryName, "primaryBinaryName");
        Objects.requireNonNull(classpath, "classpath");

        if (this.closed) {
            throw new InMemoryCompilationException("The in-memory Java compiler is closed");
        }
        if (this.compiler == null) this.compiler = ToolProvider.getSystemJavaCompiler();
        if (this.compiler == null) {
            throw new InMemoryCompilationException(
                    "Java compiler not found. Run the application with a JDK that includes jdk.compiler. Current java.home: "
                            + System.getProperty("java.home")
            );
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        this.currentDiagnostics = diagnostics;
        try {
            if (this.standardFileManager != null && (!classpath.equals(this.classpath) || sourcesChanged())) {
                closeFileManager();
            }
            if (this.standardFileManager == null) {
                this.standardFileManager = this.compiler.getStandardFileManager(
                        diagnostic -> this.currentDiagnostics.report(diagnostic), Locale.ROOT, StandardCharsets.UTF_8);
                this.classpath = classpath;
                this.inputFileManager = this.fileManagerFactory.apply(this.standardFileManager);
            }
            // Output and diagnostics belong to this request. Closing this wrapper would also close the retained manager.
            InMemoryJavaFileManager fileManager = new InMemoryJavaFileManager(this.inputFileManager);
            List<String> options = new ArrayList<>();
            options.add("-proc:none");
            options.add("--release");
            options.add("21");
            options.add("-g:source,lines,vars");
            if (!classpath.isBlank()) {
                options.add("-classpath");
                options.add(classpath);
            }
            JavaCompiler.CompilationTask task = this.compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    List.of(new StringInputObject(primaryBinaryName, sourceCode))
            );
            if (this.sources.isEmpty()) {
                var stamps = new ArrayList<SourceStamp>();
                for (Path path : this.standardFileManager.getLocationAsPaths(StandardLocation.CLASS_PATH)) {
                    stamps.add(SourceStamp.read(path));
                }
                this.sources = List.copyOf(stamps);
            }
            if (!Boolean.TRUE.equals(task.call())) {
                throw new InMemoryCompilationException(formatDiagnostics(diagnostics));
            }
            Map<String, byte[]> bytecode = ScriptBytecodeTransformer.transform(
                    fileManager.bytecode(),
                    this.inputFileManager
            );
            if (!bytecode.containsKey(primaryBinaryName)) {
                throw new InMemoryCompilationException(
                        "Compilation completed without producing the primary class " + primaryBinaryName
                );
            }
            return bytecode;
        } catch (ScriptBytecodeTransformer.TransformationException exception) {
            throw new InMemoryCompilationException(exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new InMemoryCompilationException("Unable to prepare the in-memory compiler classpath", exception);
        } catch (RuntimeException exception) {
            throw new InMemoryCompilationException("Java compilation failed unexpectedly", exception);
        } finally {
            this.currentDiagnostics = null;
        }
    }

    private boolean sourcesChanged() throws IOException {
        for (SourceStamp source : this.sources) {
            if (!source.equals(SourceStamp.read(source.path()))) return true;
        }
        return false;
    }

    /** Waits for any active compilation, then releases the retained archive handles. */
    @Override
    public synchronized void close() throws IOException {
        this.closed = true;
        closeFileManager();
    }

    private void closeFileManager() throws IOException {
        JavaFileManager previous = this.inputFileManager == null ? this.standardFileManager : this.inputFileManager;
        this.standardFileManager = null;
        this.inputFileManager = null;
        this.sources = List.of();
        this.classpath = null;
        if (previous != null) previous.close();
    }

    private record SourceStamp(Path path, FileTime modified, long size, Object fileKey) {
        private static SourceStamp read(Path path) throws IOException {
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                return new SourceStamp(path, attributes.lastModifiedTime(), attributes.size(), attributes.fileKey());
            } catch (NoSuchFileException ignored) {
                // javac permits nonexistent classpath entries; notice if one appears later.
                return new SourceStamp(path, null, 0, null);
            }
        }
    }

    private static String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        String message = diagnostics.getDiagnostics().stream()
                .map(diagnostic -> formatDiagnostic(diagnostic, Locale.ROOT))
                .collect(Collectors.joining(System.lineSeparator()));
        return message.isBlank() ? "Java compilation failed without diagnostics" : message;
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic, Locale locale) {
        String location = diagnostic.getLineNumber() == Diagnostic.NOPOS
                ? ""
                : "line " + diagnostic.getLineNumber() + ": ";
        return location + diagnostic.getMessage(locale);
    }
}
