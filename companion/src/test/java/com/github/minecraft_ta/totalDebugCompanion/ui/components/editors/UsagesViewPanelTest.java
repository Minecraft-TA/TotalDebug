package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceUsage;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceUsagePage;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.search.reference.ReferenceSearchService;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.tth05.jindex.ReferenceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UsagesViewPanelTest {
    @TempDir Path directory;

    @Test
    void matchingUsageTextIsHighlighted() throws Exception {
        onEdt(() -> {
            try (Fixture fixture = new Fixture()) {
                TreePath first = fixture.usagePath("applyFirst");
                BufferedImage before = render(fixture.tree, first);
                type(fixture.tree, "apply");
                assertFalse(SpeedSearch.matchingRanges(fixture.tree, first.getLastPathComponent().toString()).isEmpty());
                BufferedImage after = render(fixture.tree, first);
                assertFalse(java.util.Arrays.equals(
                        before.getRGB(0, 0, 700, 28, null, 0, 700),
                        after.getRGB(0, 0, 700, 28, null, 0, 700)
                ), "Matching text must paint a highlight, not only change the selected row");
            }
        });
    }

    @Test
    void typingFindsCollapsedUsagesAndArrowsCycleLeaves() throws Exception {
        onEdt(() -> {
            try (Fixture fixture = new Fixture()) {
                TreePath first = fixture.usagePath("applyFirst");
                TreePath second = fixture.usagePath("applySecond");
                fixture.tree.collapsePath(first.getParentPath());
                fixture.tree.collapsePath(second.getParentPath());
                fixture.tree.setSelectionRow(0);
                type(fixture.tree, "apply");
                assertEquals(first, fixture.tree.getSelectionPath());
                assertTrue(fixture.tree.isVisible(first));
                press(fixture.tree, KeyEvent.VK_DOWN);
                assertEquals(second, fixture.tree.getSelectionPath());
                assertTrue(fixture.tree.isVisible(second));
                press(fixture.tree, KeyEvent.VK_DOWN);
                assertEquals(first, fixture.tree.getSelectionPath());
                press(fixture.tree, KeyEvent.VK_UP);
                assertEquals(second, fixture.tree.getSelectionPath());
                press(fixture.tree, KeyEvent.VK_ESCAPE);
                assertEquals(second, fixture.tree.getSelectionPath());
                assertTrue(SpeedSearch.matchingRanges(fixture.tree, "applySecond").isEmpty());
                press(fixture.tree, KeyEvent.VK_ENTER);
                assertEquals(List.of(new NavigationTarget.UsageSite(fixture.usages.get(1), fixture.symbol.referenceQuery())), fixture.opened);
            }
        });
    }

    @Test
    void typingPreservesAnAlreadyMatchingUsage() throws Exception {
        onEdt(() -> {
            try (Fixture fixture = new Fixture()) {
                TreePath first = fixture.usagePath("applyFirst");
                fixture.tree.setSelectionPath(first);
                for (char character : "apply".toCharArray()) {
                    type(fixture.tree, String.valueOf(character));
                    assertEquals(first, fixture.tree.getSelectionPath(), "Typing must not jump to another prefix match");
                }
            }
        });
    }

    @Test
    void groupNamesRemainSearchableAndHighlighted() throws Exception {
        onEdt(() -> {
            try (Fixture fixture = new Fixture()) {
                TreePath group = fixture.usagePath("applySecond").getParentPath();
                BufferedImage before = render(fixture.tree, group);
                type(fixture.tree, "Bravo");
                assertEquals(group, fixture.tree.getSelectionPath());
                BufferedImage after = render(fixture.tree, group);
                assertFalse(java.util.Arrays.equals(before.getRGB(0, 0, 700, 28, null, 0, 700),
                        after.getRGB(0, 0, 700, 28, null, 0, 700)));
            }
        });
    }

    @Test
    void mouseAndKeyboardMenusTargetTheIntendedRowAndIgnoreEmptySpace() throws Exception {
        onEdt(() -> {
            PopupFactory previous = PopupFactory.getSharedInstance();
            var shown = new java.util.concurrent.atomic.AtomicReference<JPopupMenu>();
            PopupFactory.setSharedInstance(new PopupFactory() {
                @Override public Popup getPopup(Component owner, Component contents, int x, int y) {
                    shown.set((JPopupMenu) contents);
                    return new Popup() {
                        @Override public void show() { }
                        @Override public void hide() { }
                    };
                }
            });
            JFrame window = new JFrame();
            window.setAutoRequestFocus(false);
            window.setFocusableWindowState(false);
            try (Fixture fixture = new Fixture()) {
                window.setContentPane(fixture.panel);
                window.setBounds(-20000, -20000, 900, 600);
                window.setVisible(true);
                TreePath first = fixture.usagePath("applyFirst");
                TreePath second = fixture.usagePath("applySecond");
                Rectangle row = fixture.tree.getPathBounds(second);
                for (int eventId : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED}) {
                    fixture.tree.setSelectionPath(first);
                    fixture.tree.dispatchEvent(new MouseEvent(fixture.tree, eventId, System.currentTimeMillis(),
                            0, row.x + 4, row.y + row.height / 2, 1, true, MouseEvent.BUTTON3));
                    assertEquals(second, fixture.tree.getSelectionPath());
                    assertEquals(List.of("Open source", "Copy reference"), menuLabels(shown.get()));
                    shown.getAndSet(null).setVisible(false);
                }
                fixture.tree.dispatchEvent(new MouseEvent(fixture.tree, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                        0, 4, fixture.tree.getHeight() - 1, 1, true, MouseEvent.BUTTON3));
                assertNull(shown.get());
                fixture.tree.setSelectionPath(first.getParentPath());
                KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(fixture.tree,
                        new KeyEvent(fixture.tree, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                                KeyEvent.SHIFT_DOWN_MASK, KeyEvent.VK_F10, KeyEvent.CHAR_UNDEFINED));
                assertEquals(List.of("Copy results", "Expand branch", "Collapse branch"), menuLabels(shown.get()));
                shown.getAndSet(null).setVisible(false);
            } finally {
                window.dispose();
                PopupFactory.setSharedInstance(previous);
            }
        });
    }

    @Test
    void usageMenuOpensSourceAndCopiesAnUnambiguousReference() throws Exception {
        onEdt(() -> {
            try (Fixture fixture = new Fixture()) {
                TreePath path = fixture.usagePath("applySecond");
                fixture.tree.setSelectionPath(path);
                JPopupMenu menu = fixture.panel.createContextMenu(path);
                assertEquals(List.of("Open source", "Copy reference"), menuLabels(menu));
                var copyAction = ((JMenuItem) menu.getComponent(2)).getAction();
                assertNotNull(copyAction);
                assertEquals("sample.Bravo#applySecond(java.lang.String[], sample.Foo)",
                        copyAction.getValue(javax.swing.Action.ACTION_COMMAND_KEY));
                assertEquals(KeyStroke.getKeyStroke("ctrl C"), copyAction.getValue(javax.swing.Action.ACCELERATOR_KEY));
                ((JMenuItem) menu.getComponent(0)).doClick();
                assertEquals(List.of(new NavigationTarget.UsageSite(fixture.usages.get(1), fixture.symbol.referenceQuery())), fixture.opened);
                assertEquals("sample.Bravo#applySecond(java.lang.String[], sample.Foo)", copy(fixture.tree));
                assertNotNull(fixture.tree.getActionMap().get(fixture.tree.getInputMap().get(KeyStroke.getKeyStroke("ctrl C"))));
                assertEquals(fixture.tree.getInputMap().get(KeyStroke.getKeyStroke("shift F10")),
                        fixture.tree.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0)));
            }
        });
    }

    @Test
    void groupMenuCopiesLoadedDescendantsAndExpandsOrCollapsesTheBranch() throws Exception {
        onEdt(() -> {
            try (Fixture fixture = new Fixture()) {
                TreePath first = fixture.usagePath("applyFirst");
                TreePath second = fixture.usagePath("applySecond");
                TreePath module = first.getParentPath().getParentPath();
                fixture.tree.setSelectionPath(module);
                JPopupMenu menu = fixture.panel.createContextMenu(module);
                assertEquals(List.of("Copy results", "Expand branch", "Collapse branch"), menuLabels(menu));
                assertEquals("sample.Alpha#applyFirst()" + System.lineSeparator()
                                + "sample.Bravo#applySecond(java.lang.String[], sample.Foo)",
                        ((JMenuItem) menu.getComponent(0)).getAction().getValue(javax.swing.Action.ACTION_COMMAND_KEY));
                ((JMenuItem) menu.getComponent(3)).doClick();
                assertFalse(fixture.tree.isExpanded(module));
                assertFalse(fixture.tree.isExpanded(first.getParentPath()));
                assertEquals("sample.Alpha#applyFirst()" + System.lineSeparator()
                        + "sample.Bravo#applySecond(java.lang.String[], sample.Foo)", copy(fixture.tree));
                ((JMenuItem) menu.getComponent(2)).doClick();
                assertTrue(fixture.tree.isVisible(first));
                assertTrue(fixture.tree.isVisible(second));
                assertEquals(module, fixture.tree.getSelectionPath());
                fixture.showResults(true);
                fixture.tree.setSelectionRow(0);
                assertTrue(copy(fixture.tree).endsWith("Result set is incomplete; only loaded usages are copied."));
                assertNull(fixture.panel.createContextMenu(null));
            }
        });
    }

    @Test
    void regroupingKeepsActiveSearchUsable() throws Exception {
        var reference = new java.util.concurrent.atomic.AtomicReference<Fixture>();
        onEdt(() -> {
            Fixture fixture = new Fixture();
            reference.set(fixture);
        });
        Fixture fixture = reference.get();
        try {
            onEdt(() -> {
                type(fixture.tree, "apply");
                var regroup = UsagesViewPanel.class.getDeclaredMethod("updateGrouping", UsageTreeModel.Options.class);
                regroup.setAccessible(true);
                regroup.invoke(fixture.panel, new UsageTreeModel.Options(false, false, false, false));
            });
            onEdt(() -> {
                TreePath first = fixture.usagePath("applyFirst");
                TreePath second = fixture.usagePath("applySecond");
                assertEquals(first, fixture.tree.getSelectionPath());
                press(fixture.tree, KeyEvent.VK_DOWN);
                assertEquals(second, fixture.tree.getSelectionPath());
                press(fixture.tree, KeyEvent.VK_ENTER);
                assertEquals(1, fixture.opened.size());
            });
        } finally {
            onEdt(fixture::close);
        }
    }

    @Test
    void noMatchDoesNotMoveOrOpenAnotherUsage() throws Exception {
        onEdt(() -> {
            try (Fixture fixture = new Fixture()) {
                TreePath first = fixture.usagePath("applyFirst");
                fixture.tree.setSelectionPath(first);
                type(fixture.tree, "zzzz");
                press(fixture.tree, KeyEvent.VK_DOWN);
                assertEquals(first, fixture.tree.getSelectionPath());
                assertTrue(fixture.opened.isEmpty());
            }
        });
    }

    private final class Fixture implements AutoCloseable {
        final CodeSymbol.ClassSymbol symbol = new CodeSymbol.ClassSymbol("sample.Target");
        final List<ReferenceUsage> usages = List.of(usage(1, "sample.Alpha", "applyFirst"), usage(2, "sample.Bravo", "applySecond"));
        final List<NavigationTarget> opened = new ArrayList<>();
        final ReferenceSearchService service;
        final UsagesViewPanel panel;
        final JTree tree;

        Fixture() throws Exception {
            var sources = new RuntimeSourceCatalog(List.of(new RuntimeSnapshotBytecodeSource.Source(
                    1, directory, "logical:sample",
                    new RuntimeInventory.RuntimeModule("sample", "Sample", RuntimeInventory.ModuleKind.MOD))));
            service = new ReferenceSearchService(() -> { throw new AssertionError("UI search must not query the index"); }, sources);
            panel = new UsagesViewPanel(symbol, service, opened::add);
            var field = UsagesViewPanel.class.getDeclaredField("resultsTree");
            field.setAccessible(true);
            tree = (JTree) field.get(panel);
            showResults(false);
            tree.setSize(800, 400);
        }

        void showResults(boolean truncated) throws Exception {
            var show = UsagesViewPanel.class.getDeclaredMethod("showResult", ReferenceUsagePage.class);
            show.setAccessible(true);
            show.invoke(panel, new ReferenceUsagePage(usages, truncated));
        }

        TreePath usagePath(String name) {
            var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
            var nodes = root.depthFirstEnumeration();
            while (nodes.hasMoreElements()) {
                var node = (DefaultMutableTreeNode) nodes.nextElement();
                if (node.isLeaf() && node.toString().contains(name)) return new TreePath(node.getPath());
            }
            throw new AssertionError("Missing usage " + name);
        }

        @Override public void close() { panel.dispose(); service.close(); }
    }

    private static ReferenceUsage usage(long id, String owner, String method) {
        return new ReferenceUsage(id, ReferenceLocation.method(owner, method,
                id == 2 ? "([Ljava/lang/String;Lsample/Foo;)V" : "()V"), 1, Set.of(ReferenceKind.METHOD_INVOKE), 1);
    }

    private static List<String> menuLabels(JPopupMenu menu) {
        return java.util.Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance)
                .map(component -> ((JMenuItem) component).getText()).toList();
    }

    private static String copy(JTree tree) throws Exception {
        Clipboard clipboard = new Clipboard("usages-test");
        tree.getTransferHandler().exportToClipboard(tree, clipboard, TransferHandler.COPY);
        return (String) clipboard.getData(DataFlavor.stringFlavor);
    }

    private static BufferedImage render(JTree tree, TreePath path) {
        Component renderer = tree.getCellRenderer().getTreeCellRendererComponent(
                tree, path.getLastPathComponent(), false, false, true, tree.getRowForPath(path), false);
        renderer.setSize(700, 28);
        layout(renderer);
        var image = new BufferedImage(700, 28, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        renderer.paint(graphics);
        graphics.dispose();
        return image;
    }

    private static void layout(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) layout(child);
        }
    }

    private static void type(JTree tree, String text) {
        for (char character : text.toCharArray()) dispatch(tree, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, character);
    }

    private static void press(JTree tree, int key) { dispatch(tree, KeyEvent.KEY_PRESSED, key, KeyEvent.CHAR_UNDEFINED); }

    private static void dispatch(JTree tree, int id, int key, char character) {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(tree,
                new KeyEvent(tree, id, System.currentTimeMillis(), 0, key, character));
    }

    private static void onEdt(CheckedRunnable runnable) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try { runnable.run(); } catch (Exception exception) { throw new RuntimeException(exception); }
        });
    }

    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
}
