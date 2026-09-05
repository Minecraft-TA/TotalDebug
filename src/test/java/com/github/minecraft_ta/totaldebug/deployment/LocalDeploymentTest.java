package com.github.minecraft_ta.totaldebug.deployment;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalDeploymentTest {
    @TempDir
    Path temporaryDirectory;
    private Path project;
    private Path instance;
    private Path bundle;
    private Path developmentJar;

    @BeforeEach
    void prepare() throws Exception {
        this.project = Files.createDirectory(this.temporaryDirectory.resolve("build project"));
        this.instance = Files.createDirectory(this.temporaryDirectory.resolve("Minecraft instance"));
        Files.createDirectory(this.instance.resolve("mods"));
        Files.createDirectory(this.instance.resolve("config"));
        this.bundle = Files.createDirectories(this.project.resolve("build/local-bundle"));
        Files.writeString(this.bundle.resolve("total_debug.jar"), "new mod");
        Files.writeString(this.bundle.resolve("TotalDebugCompanion.jar"), "new companion");
        this.developmentJar = this.project.resolve("mutable/TotalDebugCompanion.jar");
        Files.createDirectories(this.developmentJar.getParent());
        Files.writeString(this.developmentJar, "new companion");
        Files.writeString(this.project.resolve("settings.gradle"), "rootProject.name = 'deployment-test'\n");
        Files.writeString(this.project.resolve("build.gradle"), """
                tasks.register('localBundle')
                apply from: '%s'
                tasks.named('deployLocal') {
                    developmentCompanionJar = layout.projectDirectory.file('mutable/TotalDebugCompanion.jar')
                }
                """.formatted(Path.of(System.getProperty("totaldebug.deploymentScript"))
                .toUri().toString()));
    }

    @Test
    void deploysOnlyOwnedFilesAndSupportsRepeatedConfigurationCachedUpdates() throws Exception {
        Path mod = this.instance.resolve("mods/total_debug.jar");
        Path companion = this.instance.resolve("total-debug/companion-app/TotalDebugCompanion.jar");
        Files.writeString(mod, "old mod");
        Files.createDirectories(companion.getParent());
        Files.writeString(companion, "old companion");
        Path config = this.instance.resolve("config/total_debug-client.toml");
        Files.writeString(config, """
                [decompilation]
                # Keep this preference and its comment.
                useCompanionApp = false
                companionDevelopmentJar = "old path"
                [custom]
                preservedValue = "keep exactly"
                """);
        List<Path> untouched = List.of(
                this.instance.resolve("mods/another-mod.jar"),
                this.instance.resolve("total-debug/state.json"),
                this.instance.resolve("total-debug/scripts/example.tdscript"),
                this.instance.resolve("total-debug/cache/runtime/inventory.json")
        );
        for (Path file : untouched) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "keep exactly");
        }

        run(false, this.instance.toString());
        assertEquals("new mod", Files.readString(mod));
        assertEquals("new companion", Files.readString(companion));
        String configured = Files.readString(config);
        assertTrue(configured.contains("useCompanionApp = false"));
        assertTrue(configured.contains("# Keep this preference and its comment."));
        assertTrue(configured.contains("preservedValue = \"keep exactly\""));
        assertTrue(configured.contains(this.developmentJar.toString().replace('\\', '/')));
        var unchangedTime = Files.getLastModifiedTime(mod);
        var unchangedConfigTime = Files.getLastModifiedTime(config);
        BuildResult unchanged = run(false, this.instance.toString());
        assertTrue(unchanged.getOutput().contains("Reusing configuration cache."));
        assertEquals(unchangedTime, Files.getLastModifiedTime(mod));
        assertEquals(unchangedConfigTime, Files.getLastModifiedTime(config));

        Files.writeString(this.bundle.resolve("TotalDebugCompanion.jar"), "next companion");
        Files.writeString(this.developmentJar, "next companion");
        run(false, this.instance.toString());
        assertEquals("next companion", Files.readString(companion));
        for (Path file : untouched) {
            assertEquals("keep exactly", Files.readString(file));
        }
        try (var paths = Files.walk(this.instance)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".totaldebug-deploy-")));
        }
    }

    @Test
    void requiresAnExplicitExistingMinecraftDirectory() throws Exception {
        assertTrue(run(true, null).getOutput().contains("Set -PtotaldebugInstanceDir="));
        assertTrue(run(true, "relative-path").getOutput().contains("existing absolute Minecraft directory"));
        assertTrue(run(true, this.temporaryDirectory.toString()).getOutput().contains("must contain mods/"));
    }

    @Test
    void rejectsVersionNamedModsWithoutChangingAnything() throws Exception {
        Path previous = this.instance.resolve("mods/total_debug-1.0.jar");
        Files.writeString(previous, "old mod");
        BuildResult result = run(true, this.instance.toString());
        assertTrue(result.getOutput().contains("Remove these version-named TotalDebug JARs manually"));
        assertEquals("old mod", Files.readString(previous));
        assertFalse(Files.exists(this.instance.resolve("mods/total_debug.jar")));
        assertFalse(Files.exists(this.instance.resolve("total-debug")));
    }

    @Test
    void validatesBothInputsBeforeReplacingEitherJar() throws Exception {
        Path mod = this.instance.resolve("mods/total_debug.jar");
        Files.writeString(mod, "old mod");
        Files.delete(this.bundle.resolve("TotalDebugCompanion.jar"));
        assertTrue(run(true, this.instance.toString()).getOutput().contains("Missing bundle JAR:"));
        assertEquals("old mod", Files.readString(mod));
    }

    @Test
    void validatesBothDestinationsBeforeReplacingEitherJar() throws Exception {
        Path mod = this.instance.resolve("mods/total_debug.jar");
        Files.writeString(mod, "old mod");
        Files.createDirectories(this.instance.resolve("total-debug/companion-app/TotalDebugCompanion.jar"));
        assertTrue(run(true, this.instance.toString()).getOutput().contains("Deployment target must be a regular file"));
        assertEquals("old mod", Files.readString(mod));
    }

    @Test
    void invalidConfigurationLeavesBothBinariesUnchanged() throws Exception {
        Path mod = this.instance.resolve("mods/total_debug.jar");
        Files.writeString(mod, "old mod");
        Files.writeString(this.instance.resolve("config/total_debug-client.toml"), "[invalid TOML");
        assertTrue(run(true, this.instance.toString()).getOutput().contains("Invalid client configuration:"));
        assertEquals("old mod", Files.readString(mod));
        assertFalse(Files.exists(this.instance.resolve("total-debug")));
    }

    @Test
    void mismatchedCompanionBuildStopsBeforeChangingTheInstance() throws Exception {
        Files.writeString(this.developmentJar, "different build");
        assertTrue(run(true, this.instance.toString()).getOutput().contains("bundle does not match the development JAR"));
        assertFalse(Files.exists(this.instance.resolve("mods/total_debug.jar")));
        assertFalse(Files.exists(this.instance.resolve("config/total_debug-client.toml")));
    }

    @Test
    void rejectsDestinationLinksOutsideTheInstance() throws Exception {
        Path outside = Files.createDirectory(this.temporaryDirectory.resolve("outside"));
        try {
            Files.createSymbolicLink(this.instance.resolve("total-debug"), outside);
        } catch (IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception);
        }
        assertTrue(run(true, this.instance.toString()).getOutput().contains("not inside the Minecraft instance"));
        assertFalse(Files.exists(this.instance.resolve("mods/total_debug.jar")));
        assertFalse(Files.exists(outside.resolve("companion-app")));
    }

    private BuildResult run(boolean shouldFail, String directory) {
        List<String> arguments = new ArrayList<>(List.of("deployLocal", "--configuration-cache", "--console=plain", "--stacktrace"));
        // Never inherit a developer's real deployment target, including the missing-setting test.
        arguments.add("-PtotaldebugInstanceDir=" + (directory == null ? "" : directory));
        GradleRunner runner = GradleRunner.create()
                .withProjectDir(this.project.toFile())
                .withArguments(arguments);
        return shouldFail ? runner.buildAndFail() : runner.build();
    }
}
