package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptCompilationService;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeTestSources.librarySource;
import static com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope.*;
import static org.junit.jupiter.api.Assertions.*;

@UiTest
class SearchEverywherePopupTest {
    @TempDir Path directory;

    @Test
    void typingKeepsResultsVisibleAndTheHeaderDragsTheWindow() throws Exception {
        var inputs = new ArrayList<IndexSource>();
        for (var type : List.of(SearchFixture.class, SearchFixtureImpl.class)) {
            String name = type.getName().replace('.', '/') + ".class";
            try (var input = type.getResourceAsStream("/" + name)) {
                byte[] bytes = input.readAllBytes();
                Path file = directory.resolve(name);
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
                inputs.add(IndexSource.classFile(0, bytes));
            }
        }
        var sources = List.of(librarySource(0, directory));
        try (var index = ClassIndex.fromSources(inputs);
             var compiler = new ScriptCompilationService(ignored -> false, ignored -> false);
             var insights = new CodeInsightService(() -> null, RuntimeSourceCatalog.empty());
             var service = new RuntimeIndexService(new Object(), snapshot -> snapshot.close());
             var binding = new RuntimeBinding(new RuntimeIndexService.ReadySnapshot("fixture", "fixture",
                     directory.resolve("index.jindex"), sources, index), directory,
                     RuntimeSnapshotBytecodeSource.fromIndexedSources(sources, index), compiler, insights)) {
            var popup = onEdt(() -> {
                var window = new SearchEverywherePopup(null, service, () -> binding, ignored -> {});
                show(window);
                return window;
            });
            try {
                var query = onEdt(() -> requireNamedComponent(popup, "searchEverywhere.query", FlatIconTextField.class));
                var results = onEdt(() -> requireNamedComponent(popup, "searchEverywhere.results", JList.class));
                onEdt(() -> query.setText("SearchFixture"));
                await(() -> results.getModel().getSize() == 2 && results.isShowing());
                onEdt(() -> {
                    query.setText("SearchFixtureImpl");
                    assertTrue(results.isShowing());
                    assertEquals(2, results.getModel().getSize(), "Typing must preserve the previous results while searching");
                    var dragSurface = requireNamedComponent(popup, "searchEverywhere.dragSurface", JComponent.class);
                    Point before = popup.getLocation();
                    dispatchWindowDrag(dragSurface, 36, 24);
                    assertEquals(new Point(before.x + 36, before.y + 24), popup.getLocation());
                });
                await(() -> results.getModel().getSize() == 1 && results.isShowing());
                onEdt(() -> {
                    var lostFocus = new WindowEvent(popup, WindowEvent.WINDOW_LOST_FOCUS);
                    for (var listener : popup.getWindowFocusListeners()) listener.windowLostFocus(lostFocus);
                    assertFalse(popup.isVisible(), "Losing focus must dismiss Search Everywhere");
                });
            } finally { onEdt(popup::dispose); }
        }
    }

    @Test
    void tabCyclesSearchCategoriesInBothDirections() throws Exception {
        try (var service = new RuntimeIndexService(new Object(), snapshot -> snapshot.close())) {
            onEdt(() -> {
                var popup = new SearchEverywherePopup(null, service, () -> null, ignored -> {});
                try { verifyCategoryCycling(popup); }
                finally { popup.dispose(); }
            });
        }
    }

    @Test
    void disposedPopupUnsubscribesAndIgnoresQueuedRuntimeStatus() throws Exception {
        var listenersField = RuntimeIndexService.class.getDeclaredField("listeners");
        listenersField.setAccessible(true);
        var messageField = SearchEverywherePopup.class.getDeclaredField("messageLabel");
        messageField.setAccessible(true);
        try (var service = new RuntimeIndexService(new Object(), snapshot -> snapshot.close())) {
            for (int cycle = 0; cycle < 3; cycle++) {
                var label = new AtomicReference<JLabel>();
                SwingUtilities.invokeAndWait(() -> {
                    var popup = new SearchEverywherePopup(null, service, () -> null, target -> {});
                    try {
                        assertEquals(1, ((Collection<?>) listenersField.get(service)).size());
                        label.set((JLabel) messageField.get(popup));
                        label.get().setText("unchanged after disposal");
                        service.waiting("queued before disposal");
                    } catch (IllegalAccessException failure) { throw new AssertionError(failure); }
                    finally { popup.dispose(); }
                });
                SwingUtilities.invokeAndWait(() -> assertEquals("unchanged after disposal", label.get().getText()));
                assertTrue(((Collection<?>) listenersField.get(service)).isEmpty());
                service.waiting("sent after disposal");
                SwingUtilities.invokeAndWait(() -> assertEquals("unchanged after disposal", label.get().getText()));
            }
        }
    }

    private static void verifyCategoryCycling(SearchEverywherePopup popup) {
        JComponent query = requireNamedComponent(popup, "searchEverywhere.query", JComponent.class);
        if (query.getFocusTraversalKeysEnabled()) {
            throw new IllegalStateException("Search query still uses Tab for focus traversal");
        }
        AbstractButton filter = requireNamedComponent(popup, "searchEverywhere.moduleFilter", AbstractButton.class);
        assertTrue(filter.isFocusable());
        assertFalse(filter.isRequestFocusEnabled());
        assertTrue(filter.getToolTipText().contains("Alt+M"));
        assertNotNull(popup.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(KeyStroke.getKeyStroke("alt M")));
        assertNotNull(query.getInputMap().get(KeyStroke.getKeyStroke("ctrl TAB")));
        assertNotNull(query.getInputMap().get(KeyStroke.getKeyStroke("ctrl shift TAB")));

        assertSelected(popup, "all");
        invokeBinding(query, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0));
        assertSelected(popup, "classes");
        invokeBinding(query, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0));
        assertSelected(popup, "symbols");
        invokeBinding(query, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK));
        assertSelected(popup, "classes");
        invokeBinding(query, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK));
        assertSelected(popup, "all");
        invokeBinding(query, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK));
        assertSelected(popup, "text");
    }

    private static void invokeBinding(JComponent component, KeyStroke keyStroke) {
        Object actionKey = component.getInputMap(JComponent.WHEN_FOCUSED).get(keyStroke);
        Action action = actionKey == null ? null : component.getActionMap().get(actionKey);
        if (action == null) {
            throw new IllegalStateException("Missing Search Everywhere binding for " + keyStroke);
        }
        action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, actionKey.toString()));
    }

    private static void assertSelected(SearchEverywherePopup popup, String category) {
        AbstractButton button = requireNamedComponent(
                popup,
                "searchEverywhere.category." + category,
                AbstractButton.class
        );
        if (!button.isSelected()) {
            throw new IllegalStateException("Search category is not selected: " + category);
        }
    }

    private static <T extends Component> T requireNamedComponent(
            Container root,
            String name,
            Class<T> type
    ) {
        T component = findNamedComponent(root, name, type);
        if (component == null) {
            throw new IllegalStateException("Component was not found: " + name);
        }
        return component;
    }

    private static <T extends Component> T findNamedComponent(Container root, String name, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (name.equals(component.getName()) && type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                T match = findNamedComponent(child, name, type);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static void dispatchWindowDrag(Component component, int dx, int dy) {
        Point screen = component.getLocationOnScreen();
        long now = System.currentTimeMillis();
        component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_PRESSED, now,
                MouseEvent.BUTTON1_DOWN_MASK, 4, 4, screen.x + 4, screen.y + 4, 1, false, MouseEvent.BUTTON1));
        component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_DRAGGED, now + 1,
                MouseEvent.BUTTON1_DOWN_MASK, 4 + dx, 4 + dy, screen.x + 4 + dx, screen.y + 4 + dy, 0, false, MouseEvent.NOBUTTON));
        component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_RELEASED, now + 2,
                0, 4 + dx, 4 + dy, screen.x + 4 + dx, screen.y + 4 + dy, 1, false, MouseEvent.BUTTON1));
    }

    static class SearchFixture { }
    static class SearchFixtureImpl { }
}
