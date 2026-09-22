package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeView;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FileTreeRootPreparationTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void firstScriptsRootIsPreparedOffEdtWithoutRefreshingOtherRoots(boolean refreshScripts) throws Exception {
        var scope = scope("game");
        var selected = new AtomicReference<>(scope);
        var view = edt(() -> new FileTreeView(selected::get, ignored -> { }));
        var tree = (LazyFileJTree) view.getViewport().getView();
        var factory = new HeldFactory();
        var existingLoads = new AtomicInteger();
        var existing = new DirectoryTreeItem("runtime") {
            @Override public List<TreeItem> loadChildren() {
                existingLoads.incrementAndGet();
                return List.of(new TreeItem("Existing"));
            }
        };
        try {
            edt(() -> { tree.setRootNodes(existing); tree.setItemFactory(factory); return null; });
            assertTrue(tree.revealItemPath("runtime", List.of("Existing")).get(5, TimeUnit.SECONDS));
            var selectedPath = edt(tree::getSelectionPath);
            var existingNode = edt(() -> ((LazyTreeNode) tree.getModel().getRoot()).getChildAt(0));
            Path scripts = Files.createDirectories(scope.paths().scripts());
            Files.writeString(scripts.resolve("First.tdscript"), "return 1;");
            var first = edt(() -> refreshScripts ? view.refreshScripts() : view.refreshDirectory(scripts));
            factory.prepared.get(5, TimeUnit.SECONDS);
            assertEquals(42, edt(() -> 42), "The EDT must remain responsive while directory registration is held");
            var second = edt(() -> view.refreshDirectory(scripts));
            edt(() -> null);
            assertEquals(1, factory.calls.get(), "Concurrent refreshes share root preparation");
            assertFalse(first.isDone());
            factory.release.complete(null);
            CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);
            edt(() -> {
                var root = (LazyTreeNode) tree.getModel().getRoot();
                assertEquals(2, root.getChildCount());
                assertSame(existingNode, root.getChildAt(1));
                assertEquals(selectedPath, tree.getSelectionPath());
                assertEquals(1, existingLoads.get(), "Installing Scripts must not rescan an unrelated loaded root");
                return null;
            });
            assertTrue(tree.revealItemPath("scripts", List.of("First.tdscript")).get(5, TimeUnit.SECONDS));
        } finally {
            factory.release.complete(null);
            edt(() -> { view.dispose(); return null; });
            scope.retire();
            scope.close();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"switch", "retire", "dispose"})
    void staleRootPreparationDisposesItsWatcherInsteadOfInstalling(String change) throws Exception {
        var original = scope("original");
        var replacement = scope("replacement");
        var selected = new AtomicReference<>(original);
        var view = edt(() -> new FileTreeView(selected::get, ignored -> { }));
        var tree = (LazyFileJTree) view.getViewport().getView();
        var factory = new HeldFactory();
        try {
            edt(() -> { tree.setItemFactory(factory); return null; });
            Path scripts = Files.createDirectories(original.paths().scripts());
            var pending = edt(() -> view.refreshDirectory(scripts));
            factory.prepared.get(5, TimeUnit.SECONDS);
            edt(() -> {
                switch (change) {
                    case "switch" -> { selected.set(replacement); view.reloadProfile(); }
                    case "retire" -> original.retire();
                    case "dispose" -> view.dispose();
                    default -> throw new AssertionError(change);
                }
                return null;
            });
            factory.release.complete(null);
            assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            factory.disposed.get(5, TimeUnit.SECONDS);
            assertEquals(0, edt(() -> ((LazyTreeNode) tree.getModel().getRoot()).getChildCount()));
        } finally {
            factory.release.complete(null);
            edt(() -> { view.dispose(); return null; });
            original.retire(); original.close();
            replacement.retire(); replacement.close();
        }
    }

    private ProjectScope scope(String name) throws Exception {
        return new ProjectScope(new Object(), CompanionProfile.forGame(Files.createDirectory(directory.resolve(name))), InstanceState.inMemory());
    }

    private static final class HeldFactory extends FileTreeItemFactory {
        final CompletableFuture<Void> prepared = new CompletableFuture<>();
        final CompletableFuture<Void> release = new CompletableFuture<>();
        final CompletableFuture<Void> disposed = new CompletableFuture<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override public FileSystemDirectoryItem createFileSystemDirectoryItem(Path path, boolean watch) {
            assertFalse(SwingUtilities.isEventDispatchThread(), "Filesystem validation and watcher registration must run off the EDT");
            calls.incrementAndGet();
            var item = new FileSystemDirectoryItem(tree, path, watch) {
                @Override public void dispose() { super.dispose(); disposed.complete(null); }
            };
            prepared.complete(null);
            try { release.get(5, TimeUnit.SECONDS); }
            catch (Exception failure) { item.dispose(); throw new AssertionError(failure); }
            return item;
        }
    }

    private static <T> T edt(Callable<T> call) throws Exception {
        var task = new FutureTask<>(call);
        SwingUtilities.invokeAndWait(task);
        return task.get(5, TimeUnit.SECONDS);
    }
}
