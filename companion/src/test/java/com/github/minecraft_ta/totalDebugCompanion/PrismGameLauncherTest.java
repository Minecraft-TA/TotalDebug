package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.sun.jna.NativeLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PrismGameLauncherTest {
    @TempDir Path root;

    @Test @EnabledOnOs(OS.WINDOWS)
    void nativeLauncherReceivesARegularOutputFileInsteadOfTheNullDevice() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Process child = new PrismGameLauncher().start(List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                NativeOutputProbe.class.getName()));
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), "Prism requires a valid regular-file output handle on Windows");
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor();
        }
    }

    public static final class NativeOutputProbe {
        public static void main(String[] args) {
            var kernel = NativeLibrary.getInstance("kernel32");
            var output = kernel.getFunction("GetStdHandle").invokePointer(new Object[]{-11});
            int kind = kernel.getFunction("GetFileType").invokeInt(new Object[]{output});
            if (kind != 1) System.exit(42); // FILE_TYPE_DISK, rather than FILE_TYPE_CHAR for NUL.
            System.out.print("launch output\n".repeat(10_000));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"minecraft", ".minecraft"})
    void commandPreservesInstanceIdentityAndPathsAsSeparateArguments(String gameFolder) throws Exception {
        Path home = Files.createDirectories(root.resolve("Prism library ü"));
        Files.writeString(home.resolve("prismlauncher.cfg"), "[General]\nInstanceDir=instances\n");
        var profile = instance(home.resolve("instances/Pack ü & more"), gameFolder);
        Path executable = executable(root.resolve("Prism application/prismlauncher.exe"));
        var launcher = new PrismGameLauncher(home, () -> List.of(executable), ignored -> { throw new AssertionError(); });
        assertEquals(List.of(executable.toString(), "--dir", home.toString(), "--launch", "Pack ü & more"), launcher.command(profile));
    }

    @Test void customInstanceDirectoryUsesTheOwningPrismDataRoot() throws Exception {
        Path home = Files.createDirectories(root.resolve("Prism"));
        Path instances = Files.createDirectories(root.resolve("custom instances"));
        Files.writeString(home.resolve("prismlauncher.cfg"), "InstanceDir=" + instances + "\n");
        var profile = instance(instances.resolve("Pack"), "minecraft");
        var launcher = new PrismGameLauncher(home, List::of, ignored -> { throw new AssertionError(); });
        assertEquals(new PrismGameLauncher.Target(home, "Pack"), launcher.target(profile));
    }

    @Test void portableLibraryPrefersItsOwnExecutable() throws Exception {
        Path home = Files.createDirectories(root.resolve("portable/UserData"));
        Files.writeString(home.resolve("prismlauncher.cfg"), "InstanceDir=instances\n");
        var profile = instance(home.resolve("instances/Pack"), "minecraft");
        Path portable = executable(home.getParent().resolve("prismlauncher.exe"));
        Path installed = executable(root.resolve("installed/prismlauncher.exe"));
        var launcher = new PrismGameLauncher(root.resolve("other"), () -> List.of(installed), ignored -> { throw new AssertionError(); });
        assertEquals(portable.toString(), launcher.command(profile).getFirst());
        assertEquals(home.toString(), launcher.command(profile).get(2));
    }

    @Test void rejectsUnrelatedLibrariesAndMissingExecutablesWithoutStartingAnything() throws Exception {
        Path home = Files.createDirectories(root.resolve("Prism"));
        Files.writeString(home.resolve("prismlauncher.cfg"), "InstanceDir=instances\n");
        var launcher = new PrismGameLauncher(home, List::of, ignored -> { throw new AssertionError(); });
        assertThrows(IOException.class, () -> launcher.target(instance(root.resolve("unrelated/Pack"), "minecraft")));
        var supported = instance(home.resolve("instances/Pack"), "minecraft");
        assertTrue(assertThrows(IOException.class, () -> launcher.command(supported)).getMessage().contains("Open Prism"));
        Files.delete(home.resolve("prismlauncher.cfg"));
        assertThrows(IOException.class, () -> launcher.target(supported));
    }

    static CompanionProfile instance(Path folder, String gameFolder) throws IOException {
        Path game = Files.createDirectories(folder.resolve(gameFolder));
        Files.writeString(folder.resolve("instance.cfg"), "[General]\nname=Different display name\n");
        return CompanionProfile.forGame(game);
    }

    static Path executable(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "fixture");
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }
}
