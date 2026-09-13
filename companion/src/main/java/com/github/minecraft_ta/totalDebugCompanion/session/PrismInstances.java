package com.github.minecraft_ta.totalDebugCompanion.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;

/** Local instance discovery only. Does not launch Prism or alter its configuration. */
public final class PrismInstances {
    public record Details(String minecraftVersion, String loader, Path icon) { }
    private PrismInstances() { }

    public static Path home() {
        String roaming = System.getenv("APPDATA");
        return roaming == null ? Path.of(System.getProperty("user.home"), ".local/share/PrismLauncher")
                : Path.of(roaming, "PrismLauncher");
    }

    public static Path gameDirectory(Path selected) {
        Path path = selected.toAbsolutePath().normalize();
        for (String child : List.of("minecraft", ".minecraft")) {
            if (Files.isDirectory(path.resolve(child))) return path.resolve(child);
        }
        return path;
    }

    public static List<CompanionProfile> discover(Path home) throws IOException {
        Path instances = home.resolve("instances");
        if (!Files.isDirectory(instances)) return List.of();
        var profiles = new ArrayList<CompanionProfile>();
        try (var children = Files.list(instances)) {
            for (Path instance : children.toList()) {
                if (!Files.isRegularFile(instance.resolve("instance.cfg"))) continue;
                Path game = gameDirectory(instance);
                if (!game.equals(instance.toAbsolutePath().normalize())) profiles.add(CompanionProfile.forGame(game));
            }
        }
        profiles.sort(Comparator.comparing(ProjectRegistry::defaultName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(profiles);
    }

    public static Details details(CompanionProfile profile) {
        Path instance = profile.workspaceDirectory().getParent();
        String minecraft = "";
        String loader = "";
        Path icon = null;
        if (instance == null) return new Details(minecraft, loader, icon);
        try {
            var pack = JsonFiles.read(instance.resolve("mmc-pack.json"));
            for (var value : JsonFiles.array(pack, "components")) {
                var component = value.getAsJsonObject();
                String id = JsonFiles.string(component, "uid");
                String version = component.has("version") ? JsonFiles.string(component, "version") : "";
                if (id.equals("net.minecraft")) minecraft = version;
                String kind = switch (id) {
                    case "net.neoforged" -> "NeoForge";
                    case "net.minecraftforge" -> "Forge";
                    case "net.fabricmc.fabric-loader" -> "Fabric";
                    case "org.quiltmc.quilt-loader" -> "Quilt";
                    default -> "";
                };
                if (!kind.isEmpty()) loader = (kind + " " + version).strip();
            }
        } catch (IOException | RuntimeException ignored) {
            // Sources can still be opened when launcher metadata is missing or incomplete.
        }
        try {
            if (instance.getParent() != null && instance.getParent().getParent() != null) {
                for (String line : Files.readAllLines(instance.resolve("instance.cfg"))) {
                    if (!line.startsWith("iconKey=")) continue;
                    String key = line.substring(8).strip();
                    if (!key.matches("[a-zA-Z0-9_. -]+") || key.equals("..")) break;
                    Path icons = instance.getParent().getParent().resolve("icons");
                    for (String extension : List.of(".png", ".jpg", ".jpeg", ".gif", "")) {
                        Path candidate = icons.resolve(key + extension);
                        if (Files.isRegularFile(candidate)) { icon = candidate; break; }
                    }
                    break;
                }
            }
        } catch (IOException ignored) { }
        return new Details(minecraft, loader, icon);
    }

}
