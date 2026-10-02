package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import com.github.minecraft_ta.totalDebugCompanion.project.CurrentProject;
import com.github.minecraft_ta.totalDebugCompanion.project.CurrentProjects;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeView;
import com.github.minecraft_ta.totalDebugCompanion.util.Workers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Scripts folder made by Companion: the instance's folders are read again on their strand, the Scripts root appears,
 * and no other root is listed again (docs/PROJECT_TREE.md).
 */
class FileTreeRootPreparationTest {
    @TempDir Path directory;

    @Test
    void aScriptsFolderMadeByCompanionShowsItsRootAndRescansNoOtherRoot() throws Exception {
        var scope = scope("game");
        Files.writeString(Files.createDirectories(scope.profile().workspaceDirectory().resolve("logs")).resolve("latest.log"), "");
        scope.folders().refresh().get(5, TimeUnit.SECONDS);
        var view = edt(() -> new FileTreeView(CurrentProjects.of(scope), ignored -> { }));
        var tree = edt(() -> (LazyFileJTree) view.getViewport().getView());
        try {
            assertTrue(tree.revealItemPath("modpack", List.of("logs"), () -> true).get(5, TimeUnit.SECONDS));
            int modpackLoads = edt(() -> tree.loads("modpack"));
            assertFalse(edt(() -> tree.hasRootNode("scripts")), "no Scripts folder, no root");

            Path scripts = Files.createDirectories(scope.paths().scripts());
            Files.writeString(scripts.resolve("First.tdscript"), "return 1;");
            view.refreshDirectory(scripts).get(5, TimeUnit.SECONDS);
            assertTrue(edt(() -> tree.hasRootNode("scripts")), "the root appears once the folders were read");
            assertTrue(tree.revealItemPath("scripts", List.of("First.tdscript"), () -> true).get(5, TimeUnit.SECONDS));
            assertEquals(modpackLoads, (int) edt(() -> tree.loads("modpack")), "showing Scripts lists no other root again");
        } finally {
            edt(() -> { view.dispose(); return null; });
            scope.retire();
            scope.close();
        }
    }

    @Test
    void aSwitchWhileTheFoldersAreReadShowsOnlyTheNewProjectsRoots() throws Exception {
        var original = scope("original");
        var replacement = scope("replacement");
        original.folders().refresh().get(5, TimeUnit.SECONDS);
        replacement.folders().refresh().get(5, TimeUnit.SECONDS);
        CurrentProject current = CurrentProjects.of(original);
        var view = edt(() -> new FileTreeView(current, ignored -> { }));
        var tree = edt(() -> (LazyFileJTree) view.getViewport().getView());
        Path scripts = Files.createDirectories(original.paths().scripts());
        try {
            var refreshed = holdingOwners(() -> {
                var refresh = view.refreshDirectory(scripts);
                // The user switches to another project while the original's folders are read.
                current.set(replacement);
                return refresh;
            });
            refreshed.handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);
            settle();
            assertFalse(edt(() -> tree.hasRootNode("scripts")), "the original's Scripts folder is not shown for the replacement");
        } finally {
            edt(() -> { view.dispose(); return null; });
            original.retire(); original.close();
            replacement.retire(); replacement.close();
        }
    }

    /** Runs {@code action} while every owner's thread is held, so the readings it asks for wait, then lets them go. */
    private static <T> T holdingOwners(Callable<T> action) throws Exception {
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        for (int owner = 0; owner < 4; owner++) {
            Workers.strand().execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(started.await(5, TimeUnit.SECONDS), "every owner's thread is held");
        try {
            T result = action.call();
            settle();
            return result;
        } finally {
            release.countDown();
        }
    }

    private ProjectScope scope(String name) throws Exception {
        return new ProjectScope(new Object(), CompanionProfile.forGame(Files.createDirectory(directory.resolve(name))), InstanceState.inMemory(), ChangeRecord.inMemory());
    }

    private static void settle() throws Exception {
        for (int step = 0; step < 3; step++) SwingUtilities.invokeAndWait(() -> { });
        Thread.sleep(200);
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static <T> T edt(Callable<T> call) throws Exception {
        var task = new FutureTask<>(call);
        SwingUtilities.invokeAndWait(task);
        return task.get(5, TimeUnit.SECONDS);
    }
}
