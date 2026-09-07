package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceUsage;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.tth05.jindex.ReferenceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageTreeModelTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void groupsRuntimeUsagesByFriendlyModuleAndContainingClass() throws Exception {
        RuntimeSourceCatalog sources = sourceCatalog();
        List<ReferenceUsage> usages = List.of(
                usage(1, "example.alpha.First", ReferenceKind.METHOD_INVOKE, 2),
                usage(2, "net.minecraft.world.level.block.Block", ReferenceKind.FIELD_READ, 1),
                usage(1, "example.beta.Second", ReferenceKind.METHOD_INVOKE, 1)
        );

        UsageTreeModel.Group root = UsageTreeModel.build(
                usages,
                sources,
                UsageTreeModel.Options.defaults()
        );

        assertEquals("Usages in runtime", root.label());
        assertEquals(3, root.siteCount());
        assertEquals(4, root.referenceCount());
        assertEquals(List.of("Example Mod (example)", "Minecraft (minecraft)"), labels(root.children()));

        UsageTreeModel.Group example = root.children().getFirst();
        assertEquals(List.of(
                "First  example.alpha",
                "Second  example.beta"
        ), labels(example.children()));
        assertEquals(1, example.children().getFirst().usages().size());
    }

    @Test
    void optionalGroupsUseRelationAndPackageWithoutDuplicatingMixedSites() throws Exception {
        RuntimeSourceCatalog sources = sourceCatalog();
        ReferenceUsage mixed = new ReferenceUsage(
                9,
                new ReferenceLocation(
                        "example.alpha.First",
                        new ReferenceLocation.Method("update", "()V")
                ),
                1,
                Set.of(ReferenceKind.FIELD_READ, ReferenceKind.FIELD_WRITE),
                3
        );

        UsageTreeModel.Group root = UsageTreeModel.build(
                List.of(mixed),
                sources,
                new UsageTreeModel.Options(true, true, true, true)
        );

        UsageTreeModel.Group usageType = root.children().getFirst();
        assertEquals("Read + Write", usageType.label());
        UsageTreeModel.Group module = usageType.children().getFirst();
        assertEquals("Example Mod (example)", module.label());
        UsageTreeModel.Group packageGroup = module.children().getFirst();
        assertEquals("example.alpha", packageGroup.label());
        assertEquals("First", packageGroup.children().getFirst().label());
        assertEquals(1, root.siteCount());
        assertEquals(3, root.referenceCount());
    }

    private RuntimeSourceCatalog sourceCatalog() throws Exception {
        Path example = Files.createDirectory(this.temporaryDirectory.resolve("example"));
        Path minecraft = Files.createDirectory(this.temporaryDirectory.resolve("minecraft"));
        return new RuntimeSourceCatalog(List.of(
                new RuntimeSnapshotBytecodeSource.Source(
                        1,
                        example,
                        "logical:example",
                        new RuntimeInventory.RuntimeModule(
                                "example",
                                "Example Mod",
                                RuntimeInventory.ModuleKind.MOD
                        )
                ),
                new RuntimeSnapshotBytecodeSource.Source(
                        2,
                        minecraft,
                        "logical:minecraft",
                        new RuntimeInventory.RuntimeModule(
                                "minecraft",
                                "Minecraft",
                                RuntimeInventory.ModuleKind.PLATFORM
                        )
                )
        ));
    }

    private static ReferenceUsage usage(
            int sourceId,
            String className,
            ReferenceKind kind,
            long occurrences
    ) {
        return new ReferenceUsage(
                Integer.toUnsignedLong(className.hashCode()),
                new ReferenceLocation(className, new ReferenceLocation.Method("run", "()V")),
                sourceId,
                Set.of(kind),
                occurrences
        );
    }

    private static List<String> labels(List<UsageTreeModel.Group> groups) {
        return groups.stream().map(UsageTreeModel.Group::label).toList();
    }
}
