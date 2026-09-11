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
    public record Project(String name, CompanionProfile profile) {
        public Project {
            if (Objects.requireNonNull(name).isBlank()) throw new IllegalArgumentException("Project name is blank");
            Objects.requireNonNull(profile);
        }
    }

    private final AppPaths paths;
    private final Map<String, Project> projects = new LinkedHashMap<>();
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
                    if (registry.projects.putIfAbsent(profile.id(), new Project(JsonFiles.string(entry, "name"), profile)) != null)
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
        return registry;
    }

    public synchronized List<Project> projects() { return List.copyOf(this.projects.values()); }

    public synchronized CompanionProfile selected() {
        Project project = this.projects.get(this.selected);
        return project == null ? null : project.profile();
    }

    public synchronized void select(CompanionProfile profile) throws IOException {
        if (profile.equals(selected())) return;
        var replacement = new LinkedHashMap<>(this.projects);
        Project previous = replacement.get(profile.id());
        String name = previous == null ? defaultName(profile) : previous.name();
        replacement.put(profile.id(), new Project(name, profile));
        JsonObject json = new JsonObject();
        json.addProperty("format", 1);
        json.addProperty("selected", profile.id());
        JsonArray entries = new JsonArray();
        for (Project project : replacement.values()) {
            JsonObject entry = project.profile().toJson();
            entry.addProperty("name", project.name());
            entries.add(entry);
        }
        json.add("projects", entries);
        JsonFiles.write(this.paths.projects(), json);
        this.projects.clear();
        this.projects.putAll(replacement);
        this.selected = profile.id();
    }

    private static String defaultName(CompanionProfile profile) {
        var path = profile.workspaceDirectory();
        if (path.getFileName() != null && path.getFileName().toString().equals("minecraft") && path.getParent() != null)
            path = path.getParent();
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }
}
