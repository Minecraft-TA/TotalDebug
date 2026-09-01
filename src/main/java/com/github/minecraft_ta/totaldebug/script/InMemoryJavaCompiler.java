package com.github.minecraft_ta.totaldebug.script;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Compiles one in-memory Java source unit and returns every generated class. */
public final class InMemoryJavaCompiler {
    public Map<String, byte[]> compile(
            String sourceCode,
            String primaryBinaryName,
            String classpath
    ) throws InMemoryCompilationException {
        Objects.requireNonNull(sourceCode, "sourceCode");
        Objects.requireNonNull(primaryBinaryName, "primaryBinaryName");
        Objects.requireNonNull(classpath, "classpath");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new InMemoryCompilationException(
                    "Java compiler not found. Run Minecraft with a JDK that includes jdk.compiler. Current java.home: "
                            + System.getProperty("java.home")
            );
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(
                diagnostics,
                Locale.ROOT,
                StandardCharsets.UTF_8
        ); InMemoryJavaFileManager fileManager = new InMemoryJavaFileManager(standardFileManager)) {
            List<String> options = new ArrayList<>();
            options.add("-proc:none");
            options.add("-g:source,lines,vars");
            if (!classpath.isBlank()) {
                options.add("-classpath");
                options.add(classpath);
            }
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    List.of(new StringInputObject(primaryBinaryName, sourceCode))
            );
            if (!Boolean.TRUE.equals(task.call())) {
                throw new InMemoryCompilationException(formatDiagnostics(diagnostics));
            }
            Map<String, byte[]> bytecode = ScriptBytecodeTransformer.transform(fileManager.bytecode());
            if (!bytecode.containsKey(primaryBinaryName)) {
                throw new InMemoryCompilationException(
                        "Compilation completed without producing the primary class " + primaryBinaryName
                );
            }
            return bytecode;
        } catch (IOException exception) {
            throw new InMemoryCompilationException("Unable to close the in-memory Java compiler", exception);
        } catch (RuntimeException exception) {
            throw new InMemoryCompilationException("Java compilation failed unexpectedly", exception);
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
