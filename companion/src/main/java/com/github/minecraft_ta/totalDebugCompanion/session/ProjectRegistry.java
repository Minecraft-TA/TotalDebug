package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totaldebug.storage.AppPaths;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Remembers existing instance directories; it never moves or deletes their contents. */
public final class ProjectRegistry {
    public record Project(String nameOverride, CompanionProfile profile) {
        public Project {
            nameOverride = nameOverride == null || nameOverride.isBlank() ? null : nameOverride.strip();
            Objects.requireNonNull(profile);
        }
        public String name() { return nameOverride == null ? defaultName(profile) : nameOverride; }
    }

    private final AppPaths paths;
    private final Map<String, Project> projects = new LinkedHashMap<>();
    private volatile List<Project> snapshot = List.of();
    private String selected;

    private ProjectRegistry(AppPaths paths) { this.paths = paths; }

    public static ProjectRegistry open(AppPaths paths) throws IOException {
        var registry = new ProjectRegistry(paths);
        if (Files.isRegularFile(paths.projects())) {
            try {
                JsonObject json = JsonFiles.read(paths.projects());
                if (JsonFiles.integer(json, "format") != 1) throw new IllegalArgumentException("Unsupported project registry format");
                registry.selected = JsonFiles.string(json, "selected");
                for (var value : JsonFiles.array(json, "projects")) {
                    var entry = value.getAsJsonObject();
                    var profile = CompanionProfile.fromJson(entry);
                    String override = entry.has("nameOverride") ? JsonFiles.string(entry, "nameOverride") : null;
                    if (registry.projects.putIfAbsent(profile.id(), new Project(override, profile)) != null)
                        throw new IllegalArgumentException("Duplicate project id: " + profile.id());
                }
                if (!registry.projects.containsKey(registry.selected)) throw new IllegalArgumentException("Selected project is missing");
            } catch (RuntimeException exception) {
                throw new IOException("Invalid project registry", exception);
            }
        } else if (Files.isRegularFile(paths.profile())) {
            // Preserve the previously selected instance when first using the registry.
            registry.select(CompanionProfile.read(paths.profile()));
            Files.delete(paths.profile());
        }
        registry.snapshot = List.copyOf(registry.projects.values()).reversed();
        return registry;
    }

    public List<Project> projects() { return this.snapshot; }

    public synchronized CompanionProfile selected() {
        Project project = this.projects.get(this.selected);
        return project == null ? null : project.profile();
    }

    public synchronized void select(CompanionProfile profile) throws IOException {
        if (profile.equals(selected())) return;
        var replacement = new LinkedHashMap<>(this.projects);
        Project previous = replacement.get(profile.id());
        replacement.remove(profile.id());
        replacement.put(profile.id(), new Project(previous == null ? null : previous.nameOverride(), profile));
        write(replacement, profile.id());
    }

    public synchronized void rename(String id, String nameOverride) throws IOException {
        var project = Objects.requireNonNull(this.projects.get(id), "Unknown project");
        var replacement = new LinkedHashMap<>(this.projects);
        replacement.put(id, new Project(nameOverride, project.profile()));
        write(replacement, this.selected);
    }

    public synchronized void forget(String id) throws IOException {
        if (Objects.equals(id, this.selected)) throw new IllegalArgumentException("Open another project before removing this one from projects");
        var replacement = new LinkedHashMap<>(this.projects);
        if (replacement.remove(id) != null) write(replacement, this.selected);
    }

    private void write(Map<String, Project> replacement, String selected) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("format", 1);
        json.addProperty("selected", selected);
        JsonArray entries = new JsonArray();
        for (Project project : replacement.values()) {
            JsonObject entry = project.profile().toJson();
            if (project.nameOverride() != null) entry.addProperty("nameOverride", project.nameOverride());
            entries.add(entry);
        }
        json.add("projects", entries);
        JsonFiles.write(this.paths.projects(), json);
        this.projects.clear();
        this.projects.putAll(replacement);
        this.selected = selected;
        this.snapshot = List.copyOf(replacement.values()).reversed();
    }

    public static String defaultName(CompanionProfile profile) {
        var path = profile.workspaceDirectory();
        if (path.getFileName() != null && List.of("minecraft", ".minecraft").contains(path.getFileName().toString()) && path.getParent() != null)
            path = path.getParent();
        if (path.getFileName() != null && path.getFileName().toString().equals("run") && path.getParent() != null)
            return path.getParent().getFileName() + " / run";
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }
}
