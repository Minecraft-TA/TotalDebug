package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.runtime.RuntimeSourceInventory;
import com.github.tth05.jindex.ClassIndex;
import io.github.classgraph.ClassGraph;
import net.minecraft.world.level.block.Block;

import java.io.File;
import java.io.IOException;
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
            if (!Files.exists(source)) {
                throw new IllegalArgumentException("Compiler classpath source does not exist: " + source);
            }
        }
    }

    public static ScriptCompilerClasspath discover() throws IOException {
        return fromSources(RuntimeSourceInventory.discover(
                TotalDebug.class,
                Block.class,
                ClassGraph.class,
                ClassIndex.class
        ));
    }

    static ScriptCompilerClasspath fromSources(List<Path> sources) {
        return new ScriptCompilerClasspath(
                sources,
                sources.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator))
        );
    }
}
