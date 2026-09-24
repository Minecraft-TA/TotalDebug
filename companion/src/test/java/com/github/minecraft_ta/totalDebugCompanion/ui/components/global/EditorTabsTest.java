package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totalDebugCompanion.testui.OffscreenPopupFactory;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel;
import com.github.minecraft_ta.totalDebugCompanion.model.EditorLocation;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.CloseButton;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.formdev.flatlaf.util.UIScale;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorTabsTest {

    @Test void replacingPreviewRefreshesItsIconWithoutChangingTheActiveTab() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var tabs = new EditorTabs();
            var image = new TestEditor(); image.icon = Icons.IMAGE_FILE;
            var active = new TestEditor();
            var text = new TestEditor(); text.icon = Icons.TEXT_FILE;
            try {
                tabs.openEditorTab(image); tabs.openEditorTab(active);
                assertSame(Icons.IMAGE_FILE, find((Container) tabs.getTabComponentAt(0), JLabel.class).getIcon());
                tabs.replacePreview(image, text);
                assertSame(Icons.TEXT_FILE, find((Container) tabs.getTabComponentAt(0), JLabel.class).getIcon());
                assertSame(active, tabs.getSelectedEditor());
                assertSame(text, tabs.editors().getFirst());
                assertTrue(image.disposed);
            } finally {
                tabs.closeMatching(editor -> true);
                tabs.analysisExecutor().shutdownNow();
            }
        });
    }

    @Test
    @UiTest
    void rightPressOnTabPaddingDoesNotSelectAnInactiveTabAndKeyboardOpensItsMenu() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorTabs tabs = new EditorTabs();
            tabs.openEditorTab(new TestEditor());
            TestEditor active = new TestEditor();
            tabs.openEditorTab(active);
            JFrame window = new JFrame();
            try {
                window.setContentPane(tabs);
                window.setSize(600, 300);
                UiTestScope.show(window);
                var bounds = tabs.getBoundsAt(0);
                tabs.dispatchEvent(mouseEvent(tabs, MouseEvent.MOUSE_PRESSED, bounds.x + 1, bounds.y + 1, MouseEvent.BUTTON3));
                assertSame(active, tabs.getSelectedEditor());
                for (KeyStroke key : new KeyStroke[]{KeyStroke.getKeyStroke("shift F10"), KeyStroke.getKeyStroke("CONTEXT_MENU")}) {
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(tabs,
                            new KeyEvent(tabs, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                                    key.getModifiers(), key.getKeyCode(), KeyEvent.CHAR_UNDEFINED));
                    assertTrue(item(OffscreenPopupFactory.showingMenuOrNull(), "Close").isEnabled());
                    assertSame(active, tabs.getSelectedEditor());
                    OffscreenPopupFactory.showingMenu().setVisible(false);
                }
            } finally {
                window.dispose();
                tabs.closeMatching(editor -> true);
            }
        });
    }

    @Test
    void tabMenusCloseTheClickedTabWithoutChangingTheActiveEditor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorTabs tabs = new EditorTabs();
            TestEditor first = new TestEditor();
            TestEditor active = new TestEditor();
            tabs.openEditorTab(first);
            tabs.openEditorTab(active);
            JPopupMenu menu = tabs.createContextMenu(0);
            assertSame(active, tabs.getSelectedEditor());
            item(menu, "Close").doClick();
            assertTrue(first.disposed);
            assertSame(active, tabs.getSelectedEditor());
            assertFalse(item(tabs.createContextMenu(0), "Close others").isEnabled());
        });
    }

    @Test
    void bulkCloseRespectsEditorSaveVetoes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorTabs tabs = new EditorTabs();
            TestEditor close = new TestEditor();
            TestEditor keep = new TestEditor();
            TestEditor unsaved = new TestEditor();
            unsaved.canClose = false;
            tabs.openEditorTab(close);
            tabs.openEditorTab(keep);
            tabs.openEditorTab(unsaved);
            item(tabs.createContextMenu(1), "Close others").doClick();
            assertTrue(close.disposed);
            assertFalse(keep.disposed);
            assertFalse(unsaved.disposed);
            assertEquals(2, tabs.getTabCount());
            item(tabs.createContextMenu(0), "Close all").doClick();
            assertTrue(keep.disposed);
            assertFalse(unsaved.disposed);
            assertEquals(1, tabs.getTabCount());
            unsaved.canClose = true;
            item(tabs.createContextMenu(0), "Close").doClick();
            assertTrue(unsaved.disposed);
        });
    }

    @Test
    void revealUsesTheClickedEditorAndLocationActionsRequireALocation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorTabs tabs = new EditorTabs();
            AtomicReference<IEditorPanel> revealed = new AtomicReference<>();
            tabs.setRevealActionProvider(editor -> new AbstractAction("Reveal in tree") {
                @Override public void actionPerformed(ActionEvent event) { revealed.set(editor); }
            });
            TestEditor file = new TestEditor();
            file.location = EditorLocation.forFile(Path.of("script.java"), null);
            TestEditor active = new TestEditor();
            tabs.openEditorTab(file);
            tabs.openEditorTab(active);
            JPopupMenu menu = tabs.createContextMenu(0);
            assertTrue(item(menu, "Copy location").isEnabled());
            item(menu, "Reveal in tree").doClick();
            assertSame(file, revealed.get());
            assertSame(active, tabs.getSelectedEditor());
            assertFalse(Arrays.stream(tabs.createContextMenu(1).getComponents())
                    .anyMatch(component -> component instanceof JMenuItem item && "Copy location".equals(item.getText())));
        });
    }

    @Test
    @UiTest
    void tabHeightIsCompactInBothThemesWithoutClippingTheHeader() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (CompanionTheme theme : CompanionTheme.available()) {
                ThemeManager.installTheme(theme);
                EditorTabs tabs = new EditorTabs();
                tabs.openEditorTab(new TestEditor());
                tabs.setSize(500, 200);
                tabs.doLayout();
                int height = tabs.getBoundsAt(0).height;
                assertEquals(UIScale.scale(UiMetrics.TAB_HEIGHT), height);
                assertTrue(height < UIScale.scale(40));
                assertTrue(height >= tabs.getTabComponentAt(0).getPreferredSize().height);
                tabs.closeMatching(editor -> true);
            }
        });
    }

    private static JMenuItem item(JPopupMenu menu, String label) {
        return Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance)
                .map(JMenuItem.class::cast).filter(item -> label.equals(item.getText())).findFirst().orElseThrow();
    }

    @Test
    void selectiveClosePreservesOtherEditorsAndSelection() throws Exception {
        EditorTabs tabs = new EditorTabs();
        TestEditor keep = new TestEditor();
        TestEditor first = new TestEditor();
        TestEditor last = new TestEditor();
        SwingUtilities.invokeAndWait(() -> {
            tabs.openEditorTab(first);
            tabs.openEditorTab(keep);
            tabs.openEditorTab(last);
            assertEquals(3, tabs.getTabCount(), "Opening on the EDT must not queue an unguarded second action");
            tabs.closeMatching(editor -> editor != keep);
            assertEquals(1, tabs.getTabCount());
            assertSame(keep, tabs.getSelectedEditor());
            assertFalse(keep.disposed);
            assertTrue(first.disposed);
            assertTrue(last.disposed);
        });
    }

    @Test
    void closingATabDisposesItsEditor() throws Exception {
        EditorTabs tabs = new EditorTabs();
        TestEditor editor = new TestEditor();
        tabs.openEditorTab(editor).get(5, TimeUnit.SECONDS);

        SwingUtilities.invokeAndWait(() -> tabs.removeTabAt(0));

        assertTrue(editor.disposed);
    }

    @Test
    void focusCompletesOnlyWhenTheEditorIsReady() throws Exception {
        EditorTabs tabs = new EditorTabs();
        TestEditor editor = new TestEditor();

        CompletableFuture<TestEditor> focused = tabs.focusOrCreateIfAbsent(
                TestEditor.class,
                candidate -> true,
                () -> editor
        );
        SwingUtilities.invokeAndWait(() -> { });

        assertFalse(focused.isDone());
        editor.ready.complete(null);
        assertSame(editor, focused.get(5, TimeUnit.SECONDS));
    }

    @Test
    void closeSlotKeepsItsWidthWhileTheButtonTracksSelectionAndHover() throws Exception {
        EditorTabs tabs = new EditorTabs();
        tabs.openEditorTab(new TestEditor()).get(5, TimeUnit.SECONDS);
        tabs.openEditorTab(new TestEditor()).get(5, TimeUnit.SECONDS);

        SwingUtilities.invokeAndWait(() -> {
            EditorTabHeader inactiveHeader = (EditorTabHeader) tabs.getTabComponentAt(0);
            EditorTabHeader selectedHeader = (EditorTabHeader) tabs.getTabComponentAt(1);
            CloseButton inactiveClose = find(inactiveHeader, CloseButton.class);
            CloseButton selectedClose = find(selectedHeader, CloseButton.class);
            Dimension reservedWidth = inactiveHeader.getPreferredSize();

            assertFalse(inactiveClose.isVisible());
            assertTrue(selectedClose.isVisible());

            inactiveHeader.dispatchEvent(mouseEvent(inactiveHeader, MouseEvent.MOUSE_ENTERED, 1, 1, MouseEvent.NOBUTTON));
            assertTrue(inactiveHeader.isHovered());
            assertTrue(inactiveClose.isVisible());
            assertEquals(reservedWidth, inactiveHeader.getPreferredSize());

            inactiveHeader.dispatchEvent(mouseEvent(inactiveHeader, MouseEvent.MOUSE_EXITED, -1, -1, MouseEvent.NOBUTTON));
            assertFalse(inactiveHeader.isHovered());
            assertFalse(inactiveClose.isVisible());
            assertEquals(reservedWidth, inactiveHeader.getPreferredSize());
        });
    }

    @Test
    void clickingTheTabTitleSelectsTheTab() throws Exception {
        EditorTabs tabs = new EditorTabs();
        tabs.openEditorTab(new TestEditor()).get(5, TimeUnit.SECONDS);
        tabs.openEditorTab(new TestEditor()).get(5, TimeUnit.SECONDS);

        SwingUtilities.invokeAndWait(() -> {
            EditorTabHeader firstHeader = (EditorTabHeader) tabs.getTabComponentAt(0);
            JLabel title = find(firstHeader, JLabel.class);

            dispatchLeftClick(title, 1, 1);

            assertEquals(0, tabs.getSelectedIndex());
        });
    }

    @Test
    void hoverStaysTransparentAndReachesTheTabbedPaneUi() throws Exception {
        EditorTabs tabs = new EditorTabs();
        tabs.openEditorTab(new TestEditor()).get(5, TimeUnit.SECONDS);
        AtomicInteger forwardedMoves = new AtomicInteger();

        SwingUtilities.invokeAndWait(() -> {
            tabs.addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent event) {
                    forwardedMoves.incrementAndGet();
                }
            });
            EditorTabHeader header = (EditorTabHeader) tabs.getTabComponentAt(0);
            JLabel title = find(header, JLabel.class);

            title.dispatchEvent(mouseEvent(title, MouseEvent.MOUSE_ENTERED, 1, 1, MouseEvent.NOBUTTON));

            assertFalse(header.isOpaque(), "The inner tab header must not paint a second hover rectangle");
            assertTrue(forwardedMoves.get() > 0, "FlatLaf must receive hover movement for the full tab cell");
        });
    }

    @Test
    void closeButtonUsesTheButtonModelForItsRolloverIcon() {
        CloseButton button = new CloseButton();

        assertSame(Icons.CLOSE_ICON, button.getIcon());
        assertSame(Icons.CLOSE_HOVERED_ICON, button.getRolloverIcon());
    }

    @Test
    void closeButtonAndMiddleClickBothDisposeTheEditor() throws Exception {
        EditorTabs tabs = new EditorTabs();
        TestEditor buttonEditor = new TestEditor();
        TestEditor middleClickEditor = new TestEditor();
        tabs.openEditorTab(buttonEditor).get(5, TimeUnit.SECONDS);
        tabs.openEditorTab(middleClickEditor).get(5, TimeUnit.SECONDS);

        SwingUtilities.invokeAndWait(() -> {
            EditorTabHeader selectedHeader = (EditorTabHeader) tabs.getTabComponentAt(1);
            find(selectedHeader, CloseButton.class).doClick();
        });
        assertTrue(middleClickEditor.disposed);

        SwingUtilities.invokeAndWait(() -> {
            EditorTabHeader remainingHeader = (EditorTabHeader) tabs.getTabComponentAt(0);
            remainingHeader.dispatchEvent(mouseEvent(
                    remainingHeader,
                    MouseEvent.MOUSE_PRESSED,
                    1,
                    1,
                    MouseEvent.BUTTON2
            ));
        });
        assertTrue(buttonEditor.disposed);
        assertEquals(0, tabs.getTabCount());
    }

    private static MouseEvent mouseEvent(Component source, int id, int x, int y, int button) {
        return new MouseEvent(source, id, System.currentTimeMillis(), 0, x, y, 1, false, button);
    }

    private static void dispatchLeftClick(Component source, int x, int y) {
        source.dispatchEvent(mouseEvent(source, MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1));
        source.dispatchEvent(mouseEvent(source, MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON1));
        source.dispatchEvent(mouseEvent(source, MouseEvent.MOUSE_CLICKED, x, y, MouseEvent.BUTTON1));
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        T match = findOrNull(root, type);
        if (match == null) {
            throw new AssertionError("Missing component " + type.getSimpleName());
        }
        return match;
    }

    private static <T extends Component> T findOrNull(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                T match = findOrNull(child, type);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    @Test void closeResolvesEditorIdentityAfterReentrantSaveCompletion() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var tabs = new EditorTabs();
            var first = new TestEditor(); var closing = new TestEditor(); var kept = new TestEditor();
            tabs.openEditorTab(first); tabs.openEditorTab(closing); tabs.openEditorTab(kept);
            closing.beforeClose = () -> tabs.closeMatching(view -> view == first);
            tabs.removeTabAt(1);
            assertEquals(List.of(kept), tabs.editors());
            assertTrue(first.disposed); assertTrue(closing.disposed); assertFalse(kept.disposed);
            tabs.closeMatching(view -> true);
            tabs.analysisExecutor().shutdownNow();
        });
    }

    private static final class TestEditor implements IEditorPanel {
        private final JPanel panel = new JPanel();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private boolean disposed;
        private boolean canClose = true;
        private Icon icon;
        private EditorLocation location = EditorLocation.empty();

        private Runnable beforeClose = () -> {};
        @Override public boolean canClose() { var action = beforeClose; beforeClose = () -> {}; action.run(); return this.canClose; }
        @Override public EditorLocation getLocation() { return this.location; }

        @Override
        public String getTitle() {
            return "test";
        }

        @Override
        public String getTooltip() {
            return "test";
        }

        @Override
        public Icon getIcon() {
            return icon;
        }

        @Override
        public Component getComponent() {
            return this.panel;
        }

        @Override
        public CompletableFuture<Void> ready() {
            return this.ready;
        }

        @Override
        public void dispose() {
            this.disposed = true;
        }
    }
}
