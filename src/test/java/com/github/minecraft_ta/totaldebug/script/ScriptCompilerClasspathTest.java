package com.github.minecraft_ta.totaldebug.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptCompilerClasspathTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void joinsOnlyExistingRuntimeSourcesWithThePlatformSeparator() throws Exception {
        Path classes = Files.createDirectory(this.temporaryDirectory.resolve("classes"));
        Path libraries = Files.createDirectory(this.temporaryDirectory.resolve("libraries"));

        ScriptCompilerClasspath classpath = ScriptCompilerClasspath.fromSources(List.of(libraries, classes));

        assertEquals(List.of(libraries, classes), classpath.sources());
        assertEquals(libraries + File.pathSeparator + classes, classpath.argument());
        assertTrue(classpath.sources().stream().allMatch(Files::exists));
    }
}
