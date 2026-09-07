package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.evaluation.InMemoryJavaCompiler;
import com.github.minecraft_ta.totaldebug.evaluation.InMemoryCompilationException;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.runtime.PreparedRuntimeSources;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public record ScriptCompilerClasspath(List<Path> sources, String argument, PreparedRuntimeSources runtime) {
    public ScriptCompilerClasspath {
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        argument = Objects.requireNonNull(argument, "argument");
        for (Path source : sources) {
            if (source.getFileSystem() != FileSystems.getDefault() || !Files.exists(source)) {
                throw new IllegalArgumentException("Compiler classpath source must be a prepared physical path: " + source);
            }
        }
    }

    public static ScriptCompilerClasspath discover() throws IOException {
        PreparedRuntimeSources runtime = TotalDebug.get().runtimeSources();
        return new ScriptCompilerClasspath(runtime.paths(), argument(runtime.paths()), runtime);
    }

    static ScriptCompilerClasspath fromSources(List<Path> sources) {
        return new ScriptCompilerClasspath(sources, argument(sources), null);
    }

    private static String argument(List<Path> sources) {
        return sources.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    Map<String, byte[]> compile(InMemoryJavaCompiler compiler, String source, String primaryClass)
            throws InMemoryCompilationException {
        try {
            return this.runtime == null ? compiler.compile(source, primaryClass, this.argument)
                    : this.runtime.withCurrentSources(() -> compiler.compile(source, primaryClass, this.argument));
        } catch (IOException exception) {
            throw new InMemoryCompilationException(exception.getMessage(), exception);
        }
    }
}
