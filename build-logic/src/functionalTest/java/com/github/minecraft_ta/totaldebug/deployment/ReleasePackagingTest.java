package com.github.minecraft_ta.totaldebug.deployment;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class ReleasePackagingTest {
    @TempDir Path project;

    @BeforeEach
    void prepare() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'release-test'\n");
        Files.writeString(project.resolve("TotalDebugCompanion.jar"), "first executable");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", "2.0.0");
        try (var jar = new JarOutputStream(Files.newOutputStream(project.resolve("mod.jar")), manifest)) {
            jar.putNextEntry(new JarEntry("META-INF/totaldebug/companion-release.properties"));
            jar.write("version=2.0.0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/jarjar/library.jar"));
            jar.write(new byte[]{1, 2, 3, 4});
            jar.closeEntry();
        }
        Files.writeString(project.resolve("build.gradle"), """
                plugins { id 'totaldebug.deployment' }
                def version = providers.gradleProperty('releaseVersion').orElse('2.0.1-test')
                def uri = version.map { "https://github.com/Minecraft-TA/TotalDebug/releases/download/v${it}/TotalDebugCompanion.jar" }
                def metadata = tasks.register('metadata', totaldebug.gradle.GenerateCompanionReleaseMetadata) {
                    companionJar = layout.projectDirectory.file('TotalDebugCompanion.jar')
                    releaseVersion = version
                    publishedVersion = '2.0.0'
                    downloadUri = uri
                    outputFile = layout.buildDirectory.file('metadata.properties')
                }
                def packaged = tasks.register('packageRelease', totaldebug.gradle.ReleaseModJar) {
                    modJar = layout.projectDirectory.file('mod.jar')
                    releaseMetadata = metadata.flatMap { it.outputFile }
                    releaseVersion = version
                    outputFile = layout.buildDirectory.file('total_debug.jar')
                }
                tasks.register('verifyPair', totaldebug.gradle.VerifyReleasePair) {
                    modJar = packaged.flatMap { it.outputFile }
                    companionJar = layout.projectDirectory.file('TotalDebugCompanion.jar')
                    releaseVersion = version
                    downloadUri = uri
                    outputDirectory = layout.buildDirectory.dir('checksums')
                }
                """);
    }

    @Test
    void preservesNestedBytesAndInvalidatesPairWhenCompanionChanges() throws Exception {
        run(false, "verifyPair");
        var output = project.resolve("build/total_debug.jar");
        byte[] first = Files.readAllBytes(output);
        String checksum = Files.readString(project.resolve("build/checksums/TotalDebugCompanion.jar.sha256"));
        try (var jar = new JarFile(output.toFile())) {
            assertArrayEquals(new byte[]{1, 2, 3, 4}, jar.getInputStream(jar.getJarEntry("META-INF/jarjar/library.jar")).readAllBytes());
            assertEquals("2.0.1-test", jar.getManifest().getMainAttributes().getValue("Implementation-Version"));
        }
        var unchanged = run(false, "verifyPair");
        assertTrue(unchanged.getOutput().contains("Reusing configuration cache"));
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":packageRelease").getOutcome());
        run(false, "verifyPair", "--rerun-tasks");
        assertArrayEquals(first, Files.readAllBytes(output), "Release packaging must be deterministic");

        Files.writeString(project.resolve("TotalDebugCompanion.jar"), "changed executable");
        var changed = run(false, "verifyPair");
        assertTrue(changed.getOutput().contains("Reusing configuration cache"));
        assertEquals(TaskOutcome.SUCCESS, changed.task(":metadata").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, changed.task(":packageRelease").getOutcome());
        assertNotEquals(checksum, Files.readString(project.resolve("build/checksums/TotalDebugCompanion.jar.sha256")));
    }

    @Test
    void rejectsChangedExecutableAgainstAnAlreadyPackagedMod() throws Exception {
        run(false, "verifyPair");
        Files.writeString(project.resolve("TotalDebugCompanion.jar"), "swapped executable");
        assertTrue(run(true, "verifyPair", "-x", "packageRelease").getOutput()
                .contains("Packaged mod metadata does not match"));
    }

    @Test
    void rejectsReusingThePublishedVersion() {
        assertTrue(run(true, "verifyPair", "-PreleaseVersion=2.0.0").getOutput()
                .contains("new, unused release version"));
    }

    private BuildResult run(boolean fail, String... tasks) {
        var arguments = new java.util.ArrayList<>(java.util.List.of(tasks));
        arguments.addAll(java.util.List.of("--configuration-cache", "--console=plain", "--stacktrace"));
        var runner = GradleRunner.create().withPluginClasspath().withProjectDir(project.toFile()).withArguments(arguments);
        return fail ? runner.buildAndFail() : runner.build();
    }
}
