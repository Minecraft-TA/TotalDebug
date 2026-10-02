package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.DirectoryTreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyFileJTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.TreeItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@UiTest
class RootRefreshTest {
    private final AtomicInteger rootLoads = new AtomicInteger();
    private final AtomicInteger folderLoads = new AtomicInteger();
    /** What the root's rows wait for while they load. */
    private volatile CountDownLatch gate = new CountDownLatch(0);

    @Test
    void refreshingARootsRowsKeepsWhatIsLoadedBelowThem() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        onEdt(() -> {
            tree.setRootNodes(root());
            return null;
        });
        try {
            assertTrue(tree.revealItemPath("pack", List.of("mods", "leaf"), () -> true).get(5, TimeUnit.SECONDS));
            assertEquals(1, this.folderLoads.get());

            // As when the changes in effect changed: only the root's own rows, such as Changes, are read again.
            onEdt(() -> {
                tree.updateRoots(List.of(root()), Map.of("pack", LazyFileJTree.Reload.ROWS));
                return null;
            });
            await(() -> this.rootLoads.get() == 2);
            // A folder read again would be read off the Swing thread right after the root's rows.
            Thread.sleep(200);
            assertEquals(1, this.folderLoads.get(), "a folder loaded below the root's rows keeps its rows");

            // As when the catalog changed: every loaded row below is read again too.
            onEdt(() -> {
                tree.updateRoots(List.of(root()), Map.of("pack", LazyFileJTree.Reload.BELOW));
                return null;
            });
            await(() -> this.rootLoads.get() == 3 && this.folderLoads.get() == 2);
        } finally {
            onEdt(() -> {
                tree.setRootNodes();
                return null;
            });
        }
    }

    @Test
    void aReloadOfTheRowsDoesNotNarrowAReloadOfEverythingBelowUnderWay() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        onEdt(() -> {
            tree.setRootNodes(root());
            return null;
        });
        CountDownLatch held = new CountDownLatch(1);
        try {
            assertTrue(tree.revealItemPath("pack", List.of("mods", "leaf"), () -> true).get(5, TimeUnit.SECONDS));
            this.gate = held;
            onEdt(() -> {
                tree.updateRoots(List.of(root()), Map.of("pack", LazyFileJTree.Reload.BELOW));
                return null;
            });
            await(() -> this.rootLoads.get() == 2);
            // A count of changes comes while the catalog's reload still reads the root's rows.
            onEdt(() -> {
                tree.updateRoots(List.of(root()), Map.of("pack", LazyFileJTree.Reload.ROWS));
                return null;
            });
            held.countDown();
            await(() -> this.folderLoads.get() == 2);
        } finally {
            held.countDown();
            onEdt(() -> {
                tree.setRootNodes();
                return null;
            });
        }
    }

    @Test
    void aRootWhoseRowsWereNeverLoadedStaysUnloaded() throws Exception {
        LazyFileJTree tree = new LazyFileJTree();
        onEdt(() -> {
            tree.setRootNodes(root());
            tree.updateRoots(List.of(root()), Map.of("pack", LazyFileJTree.Reload.BELOW));
            return null;
        });
        try {
            Thread.sleep(200);
            assertEquals(0, this.rootLoads.get(), "a root never expanded loads nothing");
        } finally {
            onEdt(() -> {
                tree.setRootNodes();
                return null;
            });
        }
    }

    private DirectoryTreeItem root() {
        return new DirectoryTreeItem("pack") {
            @Override
            public List<TreeItem> loadChildren() {
                RootRefreshTest.this.rootLoads.incrementAndGet();
                try { RootRefreshTest.this.gate.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                return List.of(folder());
            }
        };
    }

    private DirectoryTreeItem folder() {
        return new DirectoryTreeItem("mods") {
            @Override
            public List<TreeItem> loadChildren() {
                RootRefreshTest.this.folderLoads.incrementAndGet();
                return List.of(new TreeItem("leaf") { });
            }
        };
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!onEdt(condition::getAsBoolean)) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for the tree");
            Thread.sleep(20);
        }
    }
}
