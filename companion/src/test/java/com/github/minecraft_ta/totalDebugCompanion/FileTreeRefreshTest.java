package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeView;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyFileJTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyTreeNode;
import com.github.minecraft_ta.totalDebugCompanion.util.WindowFocus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    @Test
    void comingBackToCompanionListsTheLoadedScriptFoldersAgain() throws Exception {
        var scope = ProjectScope.open(new Object(), CompanionProfile.forGame(directory));
        FileTreeView[] view = new FileTreeView[1];
        try {
            Path scripts = Files.createDirectories(scope.paths().scripts());
            Files.createDirectories(scripts.resolve("nested"));
            Files.writeString(scripts.resolve("nested/Selected.tdscript"), "return 1;");
            Files.writeString(scripts.resolve("Saved.tdscript"), "return 1;");
            SwingUtilities.invokeAndWait(() -> {
                view[0] = new FileTreeView(() -> scope, ignored -> {});
                view[0].reloadProfile();
            });
            var tree = (LazyFileJTree) view[0].getViewport().getView();
            assertTrue(tree.revealItemPath("scripts", List.of("nested", "Selected.tdscript")).get(3, TimeUnit.SECONDS));
            var selection = tree.getSelectionPath();

            // An editor adds a script in the open folder and deletes one beside it while the user is away.
            Files.writeString(scripts.resolve("nested/Added.tdscript"), "");
            Files.delete(scripts.resolve("Saved.tdscript"));
            SwingUtilities.invokeAndWait(() -> WindowFocus.returned().fire());

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!(names(tree, "nested").contains("Added.tdscript") && !names(tree).contains("Saved.tdscript"))
                    && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(List.of("Added.tdscript", "Selected.tdscript"), names(tree, "nested"));
            assertEquals(List.of("nested"), names(tree));
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(tree.isExpanded(selection.getParentPath()), "the open folder stays open");
                assertEquals(selection, tree.getSelectionPath());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (view[0] != null) view[0].dispose(); });
            scope.retire();
            scope.close();
        }
    }

    @Test
    void comingBackToCompanionAddsAScriptsFolderMadeMeanwhile() throws Exception {
        var scope = ProjectScope.open(new Object(), CompanionProfile.forGame(directory));
        FileTreeView[] view = new FileTreeView[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                view[0] = new FileTreeView(() -> scope, ignored -> {});
                view[0].reloadProfile();
            });
            var tree = (LazyFileJTree) view[0].getViewport().getView();
            boolean[] before = new boolean[1];
            SwingUtilities.invokeAndWait(() -> before[0] = tree.hasRootNode("scripts"));
            assertFalse(before[0], "no Scripts folder, no root");

            // A tool makes the Scripts folder while the user is away.
            Path scripts = Files.createDirectories(scope.paths().scripts());
            Files.writeString(scripts.resolve("Made.tdscript"), "");
            SwingUtilities.invokeAndWait(() -> WindowFocus.returned().fire());
            assertTrue(awaitRevealed(tree), "coming back adds the root");
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (view[0] != null) view[0].dispose(); });
            scope.retire();
            scope.close();
        }
    }

    @Test
    void aToolMadeFromTheToolsMenuIsShownInTheLoadedTree() throws Exception {
        var scope = ProjectScope.open(new Object(), CompanionProfile.forGame(directory));
        FileTreeView[] view = new FileTreeView[1];
        try {
            Path scripts = Files.createDirectories(scope.paths().scripts());
            Files.writeString(scripts.resolve("Existing.tdscript"), "");
            SwingUtilities.invokeAndWait(() -> {
                view[0] = new FileTreeView(() -> scope, ignored -> {});
                view[0].reloadProfile();
            });
            var tree = (LazyFileJTree) view[0].getViewport().getView();
            assertTrue(tree.revealItemPath("scripts", List.of("Existing.tdscript")).get(5, TimeUnit.SECONDS));

            // The Tools menu makes the tools folder and a script in it, then navigates to the script.
            Path tool = scope.scriptFiles().create(scope.scriptFiles().create(scripts, "tools", true, ""), "Gear", false, "");
            assertTrue(view[0].revealLocalPath(tool).get(5, TimeUnit.SECONDS), "the new script is listed and shown");
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (view[0] != null) view[0].dispose(); });
            scope.retire();
            scope.close();
        }
    }

    /** Tries to reveal the script for a while, as the root is prepared off the Swing thread. */
    private static boolean awaitRevealed(LazyFileJTree tree) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (tree.revealItemPath("scripts", List.of("Made.tdscript")).get(5, TimeUnit.SECONDS)) return true;
            Thread.sleep(50);
        }
        return false;
    }

    /** The names of the rows under Scripts, or under its loaded folder {@code folder}. */
    private static List<String> names(LazyFileJTree tree, String... folder) throws Exception {
        List<String> names = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            var node = (LazyTreeNode) ((LazyTreeNode) tree.getModel().getRoot()).getChildAt(0);
            for (String name : folder) {
                for (int i = 0; i < node.getChildCount(); i++) {
                    if (node.getChildAt(i) instanceof LazyTreeNode child && child.getUserObject().getName().equals(name)) node = child;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                if (node.getChildAt(i) instanceof LazyTreeNode child) names.add(child.getUserObject().getName());
            }
        });
        return names;
    }
}
