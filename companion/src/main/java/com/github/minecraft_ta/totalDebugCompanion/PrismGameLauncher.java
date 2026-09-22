package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.PrismInstances;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** Prism owns the game process, account selection, and JVM settings. */
final class PrismGameLauncher {
    record Target(Path home, String instanceId) { }
    @FunctionalInterface interface Starter { Process start(List<String> command) throws IOException; }

    private final Path defaultHome;
    private final Supplier<List<Path>> executables;
    private final Starter starter;

    PrismGameLauncher() {
        this(PrismInstances.home(), PrismGameLauncher::installedExecutables, PrismGameLauncher::startProcess);
    }

    PrismGameLauncher(Path defaultHome, Supplier<List<Path>> executables, Starter starter) {
        this.defaultHome = defaultHome.toAbsolutePath().normalize();
        this.executables = executables;
        this.starter = starter;
    }

    Target target(CompanionProfile profile) throws IOException {
        Path instance = profile.workspaceDirectory().getParent();
        if (instance == null || instance.getParent() == null || instance.getParent().getParent() == null
                || !Files.isRegularFile(instance.resolve("instance.cfg"))
                || !PrismInstances.gameDirectory(instance).equals(profile.workspaceDirectory())) {
            throw new IOException("This project is not a Prism instance");
        }
        var homes = new LinkedHashSet<Path>();
        homes.add(instance.getParent().getParent());
        homes.add(defaultHome);
        for (Path home : homes) {
            Path config = home.resolve("prismlauncher.cfg");
            if (!Files.isRegularFile(config)) continue;
            String configured = "instances";
            for (String line : Files.readAllLines(config)) {
                if (line.startsWith("InstanceDir=")) configured = line.substring("InstanceDir=".length()).strip();
            }
            if (configured.startsWith("\"") && configured.endsWith("\"") && configured.length() >= 2)
                configured = configured.substring(1, configured.length() - 1);
            try {
                Path instances = home.resolve(configured).normalize();
                if (Files.isDirectory(instances) && Files.isSameFile(instances, instance.getParent()))
                    return new Target(home, instance.getFileName().toString());
            } catch (InvalidPathException failure) {
                throw new IOException("Invalid Prism instance directory in " + config, failure);
            }
        }
        throw new IOException("No Prism library matches this project");
    }

    List<String> command(CompanionProfile profile) throws IOException {
        Target target = target(profile);
        var candidates = new ArrayList<Path>();
        Path portableParent = target.home().getFileName() != null && target.home().getFileName().toString().equalsIgnoreCase("UserData")
                ? target.home().getParent() : null;
        for (Path folder : new Path[]{target.home(), portableParent}) {
            if (folder == null) continue;
            candidates.add(folder.resolve("prismlauncher.exe"));
            candidates.add(folder.resolve("prismlauncher"));
        }
        candidates.addAll(executables.get());
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate))
                return List.of(candidate.toAbsolutePath().normalize().toString(), "--dir", target.home().toString(),
                        "--launch", target.instanceId());
        }
        throw new IOException("Prism Launcher was not found. Open Prism, then try Play again.");
    }

    Process start(List<String> command) throws IOException { return starter.start(command); }

    private static Process startProcess(List<String> command) throws IOException {
        // Prism's Windows handoff can abort with stdout redirected to NUL. A regular
        // file also leaves Prism independent of Companion's pipes when Companion exits.
        Path output = Files.createTempFile("totaldebug-prism-" + ProcessHandle.current().pid() + "-", ".log");
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(output); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        process.onExit().thenRun(() -> {
            try { Files.deleteIfExists(output); }
            catch (IOException failure) {
                System.getLogger(PrismGameLauncher.class.getName()).log(System.Logger.Level.DEBUG,
                        "Unable to remove temporary Prism output " + output, failure);
            }
        });
        return process;
    }

    private static List<Path> installedExecutables() {
        var candidates = new LinkedHashSet<Path>();
        for (String variable : List.of("LOCALAPPDATA", "ProgramFiles")) {
            String directory = System.getenv(variable);
            if (directory != null) candidates.add(Path.of(directory,
                    variable.equals("LOCALAPPDATA") ? "Programs/PrismLauncher/prismlauncher.exe" : "PrismLauncher/prismlauncher.exe"));
        }
        try (var processes = ProcessHandle.allProcesses()) {
            processes.map(process -> process.info().command()).flatMap(Optional::stream)
                    .map(Path::of).filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.equals("prismlauncher.exe") || name.equals("prismlauncher");
                    }).forEach(candidates::add);
        }
        String searchPath = System.getenv("PATH");
        if (searchPath != null) for (String directory : searchPath.split(File.pathSeparator)) {
            if (directory.isBlank()) continue;
            try {
                candidates.add(Path.of(directory, "prismlauncher.exe"));
                candidates.add(Path.of(directory, "prismlauncher"));
            } catch (InvalidPathException ignored) { /* Other PATH entries can still identify Prism. */ }
        }
        return List.copyOf(candidates);
    }
}
