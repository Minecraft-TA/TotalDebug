package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Where a configuration's values are read from and written to in the game directory. */
public final class ConfigSources {
    /** A file holding a configuration's values; {@code label} names the world for server configurations. */
    public record Source(String label, Path path) {
        @Override
        public String toString() {
            return this.label;
        }
    }

    private ConfigSources() {
    }

    /**
     * A configuration's loaded file, or for a server configuration the file of each world, the most recently changed
     * first, and then the defaults for new worlds. A server configuration's loaded file belongs to the world open when
     * the catalog was captured, so every world is listed. Blocking for a server configuration; it lists the worlds.
     */
    public static List<Source> of(Path workspace, PackCatalog.ConfigFile file) {
        if (file.type() != PackCatalog.ConfigType.SERVER || workspace == null) {
            return file.path() == null ? List.of() : List.of(new Source(file.fileName(), file.path()));
        }
        List<Source> worlds = new ArrayList<>();
        Map<Source, FileTime> modified = new HashMap<>();
        try (DirectoryStream<Path> saves = Files.newDirectoryStream(workspace.resolve("saves"), Files::isDirectory)) {
            for (Path world : saves) {
                Path path = world.resolve("serverconfig").resolve(file.fileName());
                if (!Files.isRegularFile(path)) continue;
                Source source = new Source(world.getFileName().toString(), path);
                worlds.add(source);
                modified.put(source, Files.getLastModifiedTime(path));
            }
        } catch (IOException noSaves) {
            // A pack that never created a world has no server configuration yet.
        }
        worlds.sort(Comparator.comparing((Source source) -> modified.get(source)).reversed());
        Path defaults = workspace.resolve("defaultconfigs").resolve(file.fileName());
        if (Files.isRegularFile(defaults)) worlds.add(new Source("New worlds", defaults));
        return worlds;
    }
}
