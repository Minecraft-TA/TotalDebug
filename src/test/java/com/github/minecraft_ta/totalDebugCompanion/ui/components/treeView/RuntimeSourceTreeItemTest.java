package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeInventory;
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
        RuntimeInventory.RuntimeModule module = new RuntimeInventory.RuntimeModule("example", "Example Mod");
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

    private static RuntimeSnapshotBytecodeSource.Source source(int id, Path path, String moduleId) {
        RuntimeInventory.RuntimeModule module = new RuntimeInventory.RuntimeModule(moduleId, "Example Mod");
        return new RuntimeSnapshotBytecodeSource.Source(id, path, path.toUri().toString(), module);
    }
}
