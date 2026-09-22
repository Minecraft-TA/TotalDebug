package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.TextResult;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.Result;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource.Source;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory.ModuleKind;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory.RuntimeModule;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Container;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@UiTest
class SearchEverywhereLayoutTest {
    @Test void longResultsStayWithinTheViewportWhenNavigatingAndResizing() throws Exception {
        try (var service = new RuntimeIndexService(new Object(), snapshot -> snapshot.close())) {
        SwingUtilities.invokeAndWait(() -> {
            var popup = new SearchEverywherePopup(null, service, () -> null, ignored -> {});
            try {
                var catalog = SearchEverywherePopup.class.getDeclaredField("sourceCatalog");
                catalog.setAccessible(true);
                catalog.set(popup, new RuntimeSourceCatalog(List.of(new Source(1, Path.of("fixture.jar"), "fixture:/test",
                        new RuntimeModule("fixture", "A long module name ".repeat(20), ModuleKind.MOD)))));
                @SuppressWarnings("unchecked") var list = (JList<Result>) find(popup, JList.class);
                list.setListData(new Result[]{new TextResult("Short", new int[]{1}),
                        new TextResult("A very long result ".repeat(30), new int[]{1})});
                var scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, list);
                for (int width : List.of(820, 360)) {
                    scroll.setSize(width, 120);
                    scroll.doLayout();
                    scroll.getViewport().doLayout();
                    assertEquals(scroll.getViewport().getExtentSize().width, list.getWidth());
                    list.setSelectedIndex(1);
                    list.ensureIndexIsVisible(1);
                    assertEquals(0, scroll.getViewport().getViewPosition().x);
                    assertFalse(scroll.getHorizontalScrollBar().isVisible());
                    var row = (Container) list.getCellRenderer().getListCellRendererComponent(list, list.getModel().getElementAt(0), 0, true, true);
                    row.setSize(list.getWidth(), list.getFixedCellHeight());
                    row.doLayout();
                    var module = ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.EAST);
                    assertTrue(module.getWidth() <= list.getWidth() / 3);
                    assertEquals(list.getWidth(), module.getX() + module.getWidth());
                }
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure);
            } finally { popup.dispose(); }
        });
        }
    }

    @Test void moduleSelectionButtonsStillApplyToAllModules() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var selection = new AtomicReference<Set<String>>(Set.of());
            var popup = new ModuleFilterPopup(List.of(new RuntimeModule("one", "One", ModuleKind.MOD),
                    new RuntimeModule("two", "Two", ModuleKind.MOD)), Set.of(), selection::set);
            button(popup, "All").doClick(0);
            assertEquals(Set.of("one", "two"), selection.get());
            button(popup, "None").doClick(0);
            assertEquals(Set.of(), selection.get());
            button(popup, "Invert").doClick(0);
            assertEquals(Set.of("one", "two"), selection.get());
        });
    }

    private static JButton button(Container parent, String text) {
        for (var child : parent.getComponents()) {
            if (child instanceof JButton button && button.getText().equals(text)) return button;
            if (child instanceof Container container) { var found = button(container, text); if (found != null) return found; }
        }
        return null;
    }
    private static <T> T find(Container parent, Class<T> type) {
        for (var child : parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) { var found = find(container, type); if (found != null) return found; }
        }
        return null;
    }
}
