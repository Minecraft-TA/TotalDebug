package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totalDebugCompanion.model.EditorLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class EditorBreadcrumbsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void assignsRuntimeSemanticsToModulePackageClassAndMember() {
        NavigationTarget.RuntimeClass source = new NavigationTarget.RuntimeClass("net.minecraft.world.level.block.Block");
        var member = new JavaBreadcrumbResolver.Member(
                "update()",
                new NavigationTarget.RuntimeDeclaration(new RuntimeMember.Method(
                        source.binaryName(),
                        "update",
                        "()V"
                )),
                100
        );

        List<BreadcrumbSegment> segments = EditorBreadcrumbs.create(
                new EditorLocation(
                        "Minecraft",
                        "minecraft",
                        List.of("net", "minecraft", "world", "level", "block", "Block.java"),
                        "runtime"
                ),
                source,
                member
        );

        assertEquals(List.of(
                "Minecraft", "net", "minecraft", "world", "level", "block", "Block.java", "update()"
        ), segments.stream().map(BreadcrumbSegment::label).toList());
        assertInstanceOf(NavigationTarget.ModuleSearch.class, segments.getFirst().target());
        assertEquals("net.minecraft.world.level.block", assertInstanceOf(
                NavigationTarget.RuntimePackage.class,
                segments.get(5).target()
        ).packageName());
        assertEquals(source, segments.get(6).target());
        assertEquals(member.target(), segments.getLast().target());
    }

    @Test
    void assignsLocalFoldersAndFileTargets() {
        Path root = this.temporaryDirectory.resolve("scripts");
        Path file = root.resolve("tools/Test.java");

        List<BreadcrumbSegment> segments = EditorBreadcrumbs.create(
                new EditorLocation("scripts", List.of("tools", "Test.java"), file.toString()),
                new NavigationTarget.LocalFile(file),
                null
        );

        assertEquals(root.toAbsolutePath().normalize(), assertInstanceOf(
                NavigationTarget.LocalDirectory.class,
                segments.getFirst().target()
        ).path());
        assertEquals(root.resolve("tools").toAbsolutePath().normalize(), assertInstanceOf(
                NavigationTarget.LocalDirectory.class,
                segments.get(1).target()
        ).path());
        assertInstanceOf(NavigationTarget.LocalFile.class, segments.getLast().target());
    }
}
