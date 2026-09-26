package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeView;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.ZipFileRootItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyFileJTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyTreeNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class LocalModSourcesTest {
    @TempDir Path game;

    @Test void browsesArchivesWithoutCreatingAnIndexOrScripts() throws Exception {
        Path mods = Files.createDirectory(game.resolve("mods"));
        Path archive = mods.resolve("sample.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write("{}".getBytes());
        }
        Files.writeString(mods.resolve("ignored.jar.disabled"), "disabled");
        var scope = ProjectScope.open(new Object(), CompanionProfile.forGame(game));
        try {
            assertNull(scope.runtime());
            assertEquals(1, scope.sources().modules().size());
            assertEquals("sample.jar", scope.sources().modules().getFirst().displayName());
            assertEquals("pack.mcmeta", new ZipFileRootItem(archive).loadChildren().getFirst().getName());
            SwingUtilities.invokeAndWait(() -> {
                var view = new FileTreeView(() -> scope, ignored -> { });
                view.reloadProfile();
                var tree = (LazyFileJTree) view.getViewport().getView();
                var root = tree.getModel().getRoot();
                assertEquals(2, tree.getModel().getChildCount(root));
                assertEquals("modpack", ((LazyTreeNode) tree.getModel().getChild(root, 0)).getUserObject().getName());
                assertEquals("runtime", ((LazyTreeNode) tree.getModel().getChild(root, 1)).getUserObject().getName());
                tree.setRootNodes();
            });
        } finally { scope.retire(); scope.close(); }
        assertFalse(Files.exists(game.resolve("total-debug")));
    }
}
