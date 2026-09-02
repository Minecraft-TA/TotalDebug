package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record ScriptCompilerClasspath(List<Path> sources, String argument) {
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
        return fromSources(TotalDebug.get().runtimeSources().paths());
    }

    static ScriptCompilerClasspath fromSources(List<Path> sources) {
        return new ScriptCompilerClasspath(
                sources,
                sources.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator))
        );
    }
}
