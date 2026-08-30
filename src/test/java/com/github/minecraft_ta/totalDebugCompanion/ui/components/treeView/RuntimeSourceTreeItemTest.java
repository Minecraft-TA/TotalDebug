package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeInventory;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.TreeItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class RuntimeSourceTreeItemTest {
    @Test
    void exposesDirectoryClassesAsRuntimeBinaryNames(@TempDir Path directory) throws Exception {
        Path packageDirectory = Files.createDirectories(directory.resolve("example/inner"));
        Files.write(packageDirectory.resolve("Target$Nested.class"), new byte[]{1});
        RuntimeSnapshotBytecodeSource.Source source = source(3, directory, "example");

        RuntimeSourceTreeItem tree = new RuntimeSourceTreeItem(source);
        RuntimeSourceTreeItem.RuntimeDirectoryEntry example = assertInstanceOf(
                RuntimeSourceTreeItem.RuntimeDirectoryEntry.class,
                tree.loadChildren().getFirst()
        );
        RuntimeSourceTreeItem.RuntimeDirectoryEntry inner = assertInstanceOf(
                RuntimeSourceTreeItem.RuntimeDirectoryEntry.class,
                example.loadChildren().getFirst()
        );
        RuntimeSourceTreeItem.RuntimeFileEntry target = assertInstanceOf(
                RuntimeSourceTreeItem.RuntimeFileEntry.class,
                inner.loadChildren().getFirst()
        );

        assertEquals("example.inner.Target$Nested", target.binaryName());
    }

    @Test
    void keepsMultiplePhysicalSourcesDistinctWithinOneModule(@TempDir Path directory) throws Exception {
        Path first = Files.createDirectories(directory.resolve("first"));
        Path second = Files.createDirectories(directory.resolve("second"));
        RuntimeInventory.RuntimeModule module = new RuntimeInventory.RuntimeModule(
                "example",
                "Example Mod",
                RuntimeInventory.ModuleKind.MOD
        );
        RuntimeModuleTreeItem item = new RuntimeModuleTreeItem(
                module,
                List.of(
                        new RuntimeSnapshotBytecodeSource.Source(1, first, first.toUri().toString(), module),
                        new RuntimeSnapshotBytecodeSource.Source(2, second, second.toUri().toString(), module)
                )
        );

        List<String> names = item.loadChildren().stream().map(treeItem -> treeItem.getName()).toList();
        assertEquals(List.of("first [source 1]", "second [source 2]"), names);
    }

    @Test
    void keepsPriorityModulesDirectAndCollectsLibrariesUnderOneNode(@TempDir Path directory) throws Exception {
        Path minecraftPath = Files.createDirectories(directory.resolve("minecraft"));
        Path neoforgePath = Files.createDirectories(directory.resolve("neoforge"));
        Path modPath = Files.createDirectories(directory.resolve("example"));
        Path libraryPath = Files.createDirectories(directory.resolve("asm"));
        Path javaPath = Files.createDirectories(directory.resolve("java"));
        RuntimeInventory.RuntimeModule minecraft = new RuntimeInventory.RuntimeModule(
                "minecraft",
                "Minecraft",
                RuntimeInventory.ModuleKind.PLATFORM
        );
        RuntimeInventory.RuntimeModule neoforge = new RuntimeInventory.RuntimeModule(
                "neoforge",
                "NeoForge",
                RuntimeInventory.ModuleKind.PLATFORM
        );
        RuntimeInventory.RuntimeModule mod = new RuntimeInventory.RuntimeModule(
                "example",
                "Example Mod",
                RuntimeInventory.ModuleKind.MOD
        );
        RuntimeInventory.RuntimeModule library = new RuntimeInventory.RuntimeModule(
                "asm",
                "ASM",
                RuntimeInventory.ModuleKind.LIBRARY
        );
        RuntimeInventory.RuntimeModule javaRuntime = new RuntimeInventory.RuntimeModule(
                "java-runtime",
                "Java Runtime",
                RuntimeInventory.ModuleKind.JAVA_RUNTIME
        );
        RuntimeSourceCatalog catalog = new RuntimeSourceCatalog(List.of(
                        new RuntimeSnapshotBytecodeSource.Source(
                                0,
                                minecraftPath,
                                minecraftPath.toUri().toString(),
                                minecraft
                        ),
                        new RuntimeSnapshotBytecodeSource.Source(
                                1,
                                neoforgePath,
                                neoforgePath.toUri().toString(),
                                neoforge
                        ),
                        new RuntimeSnapshotBytecodeSource.Source(2, modPath, modPath.toUri().toString(), mod),
                        new RuntimeSnapshotBytecodeSource.Source(
                                3,
                                libraryPath,
                                libraryPath.toUri().toString(),
                                library
                        ),
                        new RuntimeSnapshotBytecodeSource.Source(
                                4,
                                javaPath,
                                "jrt:/",
                                javaRuntime
                        )
                ));

        List<TreeItem> roots = FileTreeView.runtimeItems(catalog);

        assertEquals(List.of(
                        "Minecraft [minecraft]",
                        "NeoForge [neoforge]",
                        "Example Mod [example]",
                        "runtime-libraries",
                        "Java Runtime [java-runtime]"
                ),
                roots.stream().map(item -> item.getName()).toList());
        RuntimeLibrariesTreeItem libraries = assertInstanceOf(RuntimeLibrariesTreeItem.class, roots.get(3));
        assertEquals(List.of("ASM [asm]"),
                libraries.loadChildren().stream().map(item -> item.getName()).toList());
        assertEquals("Libraries", libraries.getPresentation().primary());
        assertEquals("1 module", libraries.getPresentation().secondary());
        assertEquals(
                List.of("runtime-libraries", "ASM [asm]", "org", "objectweb", "asm"),
                FileTreeView.runtimeDirectoryPath(library, List.of("org", "objectweb", "asm"))
        );
        assertEquals(
                List.of("Minecraft [minecraft]", "net", "minecraft"),
                FileTreeView.runtimeDirectoryPath(minecraft, List.of("net", "minecraft"))
        );
    }

    private static RuntimeSnapshotBytecodeSource.Source source(int id, Path path, String moduleId) {
        RuntimeInventory.RuntimeModule module = new RuntimeInventory.RuntimeModule(
                moduleId,
                "Example Mod",
                RuntimeInventory.ModuleKind.MOD
        );
        return new RuntimeSnapshotBytecodeSource.Source(id, path, path.toUri().toString(), module);
    }
}
