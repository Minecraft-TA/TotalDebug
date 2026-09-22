package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeView;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyFileJTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FileTreeRefreshTest {
    @TempDir Path directory;

    @Test
    void creatingAScriptAndRefreshingTheProfilePreservesTheOpenTree() throws Exception {
        var scope = ProjectScope.open(new Object(), CompanionProfile.forGame(directory));
        try {
            Path scripts = Files.createDirectories(scope.paths().scripts());
            Files.createDirectories(scripts.resolve("nested"));
            Files.writeString(scripts.resolve("nested/Selected.tdscript"), "return 1;");
            Files.writeString(scripts.resolve("Saved.tdscript"), "return 1;");
            FileTreeView[] view = new FileTreeView[1];
            SwingUtilities.invokeAndWait(() -> {
                view[0] = new FileTreeView(() -> scope, ignored -> {});
                view[0].reloadProfile();
            });
            var tree = (LazyFileJTree) view[0].getViewport().getView();
            assertTrue(tree.revealItemPath("scripts", List.of("nested", "Selected.tdscript")).get(3, TimeUnit.SECONDS));
            var selection = tree.getSelectionPath();
            Files.writeString(scripts.resolve("NewScript.tdscript"), "");
            view[0].refreshDirectory(scripts).get(3, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(tree.isExpanded(selection.getParentPath()));
                assertEquals(selection, tree.getSelectionPath());
                view[0].reloadProfile();
                assertTrue(tree.isExpanded(selection.getParentPath()), "Creating a script collapsed the existing folder");
                assertEquals(selection, tree.getSelectionPath());
            });
            assertTrue(tree.revealItemPath("scripts", List.of("NewScript.tdscript")).get(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(tree.isExpanded(selection.getParentPath()));
                tree.setRootNodes();
            });
        } finally {
            scope.retire();
            scope.close();
        }
    }
}
