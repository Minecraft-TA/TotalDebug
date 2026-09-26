package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyResult;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.RuntimeMember;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@UiTest
class SourceResultMenusTest {
    @Test
    void searchMenuCopiesQualifiedReferencesAndOpensItsCapturedResult() throws Exception {
        try (var service = new RuntimeIndexService(new Object(), snapshot -> snapshot.close())) {
            SwingUtilities.invokeAndWait(() -> {
                List<NavigationTarget> opened = new ArrayList<>();
                var popup = new SearchEverywherePopup(null, service, () -> null, () -> null, () -> null, new ItemIconService(), opened::add);
                try {
                    DefaultListModel<SearchEverywhereSearch.Result> model = model(popup, "resultModel");
                    model.addElement(new SearchEverywhereSearch.ClassResult("example.First", "First", "example", 0, 0));
                    model.addElement(new SearchEverywhereSearch.ClassResult("example.Second", "Second", "example", 0, 0));
                    JPopupMenu menu = popup.createResultMenu(1);
                    assertSame(Icons.JUMP_TO_SOURCE, ((JMenuItem) menu.getComponent(0)).getIcon());
                    assertEquals("example.Second", ((JMenuItem) menu.getComponent(2)).getActionCommand());
                    ((JMenuItem) menu.getComponent(0)).doClick(0);
                    assertEquals(List.of(new NavigationTarget.RuntimeClass("example.Second")), opened);
                    assertEquals(0, popup.createResultMenu(-1).getComponentCount());
                    var literal = new SearchEverywhereSearch.TextResult("literal", new int[]{0});
                    model.addElement(literal);
                    JPopupMenu literalMenu = popup.createResultMenu(2);
                    assertEquals("Find usages", ((JMenuItem) literalMenu.getComponent(0)).getText());
                    assertEquals("Copy value", ((JMenuItem) literalMenu.getComponent(2)).getText());
                } finally { popup.dispose(); }
            });
        }
    }

    @Test
    void hierarchyMenuKeepsTheExactOverloadInItsReferenceAndNavigation() throws Exception {
        try (var service = new CodeInsightService(() -> null, RuntimeSourceCatalog.empty())) {
            SwingUtilities.invokeAndWait(() -> {
                List<NavigationTarget> opened = new ArrayList<>();
                var popup = new ImplementationChooserPopup(null, service, opened::add);
                try {
                    var method = new CodeSymbol.MethodSymbol("example.Target", "run", "(I)V");
                    DefaultListModel<HierarchyResult> model = model(popup, "listModel");
                    model.addElement(new HierarchyResult(method, 0));
                    var menu = popup.createResultMenu(0);
                    assertEquals("example.Target#run(I)V", ((JMenuItem) menu.getComponent(2)).getActionCommand());
                    ((JMenuItem) menu.getComponent(0)).doClick(0);
                    assertEquals(List.of(new NavigationTarget.RuntimeDeclaration(RuntimeMember.from(method))), opened);
                } finally { popup.dispose(); }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> DefaultListModel<T> model(Object owner, String name) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (DefaultListModel<T>) field.get(owner);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
