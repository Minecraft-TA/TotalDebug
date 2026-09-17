package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItem;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItemKind;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;

import org.junit.jupiter.api.Test;

import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseListPopupTest {

    @Test
    void longParametersElideWithoutClippingShortCompletionLists() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (var theme : CompanionTheme.available()) {
                ThemeManager.installTheme(theme);
                var popup = new CodeCompletionPopup(null);
                try {
                    var item = new CompletionItem(null);
                    item.setKind(CompletionItemKind.METHOD);
                    item.setPresentation("methodWithALongSignature", "(" + "Argument argument, ".repeat(20) + ")", "void");
                    var scroll = (JScrollPane) popup.getContentPane().getComponent(0);
                    var list = (JList<?>) scroll.getViewport().getView();
                    for (int count : new int[]{1, 2}) {
                        popup.setItems(Collections.nCopies(count, item));
                        assertFalse(scroll.getHorizontalScrollBar().isVisible());
                        assertTrue(scroll.getViewport().getHeight() >= list.getCellBounds(0, count - 1).height,
                                theme.id() + ": the horizontal scrollbar must not consume a completion row's height");
                        assertFalse(scroll.getVerticalScrollBar().isVisible());
                    }
                    item.setPresentation("shortMethod", "()", "void");
                    popup.setItems(List.of(item));
                    assertFalse(scroll.getHorizontalScrollBar().isVisible());
                    assertTrue(scroll.getViewport().getHeight() >= list.getCellBounds(0, 0).height);
                } finally {
                    popup.dispose();
                }
            }
        });
    }

    @Test
    void preservesSelectionByDeclarationAndAcceptsTabOnlyWithoutModifiers() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var popup = new CodeCompletionPopup(null) {
                @Override public boolean isVisible() { return true; }
            };
            try {
                var first = completion("first");
                var second = completion("second");
                popup.setItems(List.of(first, second));
                var scroll = (JScrollPane) popup.getContentPane().getComponent(0);
                var list = (JList<?>) scroll.getViewport().getView();
                list.setSelectedIndex(1);
                var replacement = completion("second");
                popup.setItems(List.of(replacement, completion("first")));
                assertEquals(replacement, list.getSelectedValue());
                AtomicInteger accepts = new AtomicInteger();
                popup.setKeyEnterListener(ignored -> accepts.incrementAndGet());
                var listenerField = BaseListPopup.class.getDeclaredField("listener");
                listenerField.setAccessible(true);
                var listener = (KeyAdapter) listenerField.get(popup);
                var editor = new JTextArea();
                var tab = new KeyEvent(editor, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_TAB, '\t');
                listener.keyPressed(tab);
                assertEquals(1, accepts.get());
                assertTrue(tab.isConsumed());
                var previous = new KeyEvent(editor, KeyEvent.KEY_PRESSED, 0,
                        InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_TAB, '\t');
                listener.keyPressed(previous);
                assertFalse(previous.isConsumed());
                assertEquals(1, accepts.get());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            finally { popup.dispose(); }
        });
    }

    private static CompletionItem completion(String name) {
        var item = new CompletionItem(null);
        item.setKind(CompletionItemKind.METHOD);
        item.setPresentation(name, "()", "void");
        return item;
    }

    @Test
    void acceptingASelectionInvokesOnlyTheCurrentEditorHandler() throws Exception {
        AtomicInteger firstEditorAccepts = new AtomicInteger();
        AtomicInteger secondEditorAccepts = new AtomicInteger();
        TestItem item = new TestItem();

        AtomicReference<BaseListPopup<TestItem>> popupHolder = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            BaseListPopup<TestItem> popup = new BaseListPopup<>(null);
            JList<TestItem> list = new JList<>(new DefaultListModel<>());
            popup.setList(list);
            popup.setItems(List.of(item));
            popup.setKeyEnterListener(ignored -> firstEditorAccepts.incrementAndGet());
            popup.setKeyEnterListener(ignored -> secondEditorAccepts.incrementAndGet());
            popupHolder.set(popup);
        });

        try {
            Method accept = BaseListPopup.class.getDeclaredMethod("runEnterKeyListener");
            accept.setAccessible(true);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    accept.invoke(popupHolder.get());
                } catch (ReflectiveOperationException exception) {
                    throw new RuntimeException(exception);
                }
            });

            assertEquals(0, firstEditorAccepts.get());
            assertEquals(1, secondEditorAccepts.get());
        } finally {
            SwingUtilities.invokeAndWait(popupHolder.get()::dispose);
        }
    }

    private static final class TestItem { }
}
