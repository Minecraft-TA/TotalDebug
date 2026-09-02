package com.github.minecraft_ta.totaldebug.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeSourceInventoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesRuntimeDiscoveryPrecedence() throws Exception {
        Path first = Files.createFile(this.temporaryDirectory.resolve("z-authoritative.jar"));
        Path second = Files.createFile(this.temporaryDirectory.resolve("a-shadow.jar"));

        List<Path> sources = RuntimeSourceInventory.existingSources(new LinkedHashSet<>(List.of(first, second)));

        assertEquals(List.of(first, second), sources);
    }

    @Test
    void excludesAClasspathDistributionThatShadowsAnAlreadyOwnedAnchor() throws Exception {
        Path authoritative = jar("authoritative.jar", "java/lang/String.class");
        Path shadow = jar("shadow.jar", "java/lang/String.class");
        Path unique = jar("unique.jar", "example/Unique.class");
        LinkedHashSet<Path> sources = new LinkedHashSet<>(List.of(authoritative));

        RuntimeSourceInventory.addClasspathSources(sources, List.of(shadow, unique), String.class);

        assertEquals(List.of(authoritative, unique), List.copyOf(sources));
    }

    @Test
    void findsTheDiscoveredSourcesThatOwnAnchorClasses() throws Exception {
        Path unrelated = jar("unrelated.jar", "example/Unrelated.class");
        Path stringOwner = jar("string-owner.jar", "java/lang/String.class");
        Path inventoryOwner = jar(
                "inventory-owner.jar",
                "com/github/minecraft_ta/totaldebug/runtime/RuntimeSourceInventory.class"
        );

        var owners = RuntimeSourceInventory.sourcesContaining(
                List.of(unrelated, stringOwner, inventoryOwner),
                String.class,
                RuntimeSourceInventory.class
        );

        assertEquals(stringOwner, owners.get(String.class));
        assertEquals(inventoryOwner, owners.get(RuntimeSourceInventory.class));
    }

    @Test
    void resolvesRuntimeModuleLocationsThroughTheirFilesystemProvider() throws Exception {
        Path archive = jar("module.jar", "example/Dependency.class");
        try (var filesystem = FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Map.of())) {
            Path root = filesystem.getPath("/");
            assertEquals(root, RuntimeSourceInventory.modulePath("dependency", root.toUri()));
        }
    }

    @Test
    void skipsGeneratedAndJdkModulesButRejectsUnresolvableSources() throws Exception {
        assertNull(RuntimeSourceInventory.modulePath("synthetic", null));
        assertNull(RuntimeSourceInventory.modulePath("java.base", URI.create("jrt:/java.base")));
        assertThrows(IOException.class, () -> RuntimeSourceInventory.modulePath(
                "unresolvable", URI.create("missing-provider:/module")));
    }

    private Path jar(String fileName, String entryName) throws Exception {
        Path jar = this.temporaryDirectory.resolve(fileName);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(new byte[]{1});
            output.closeEntry();
        }
        return jar;
    }
}
