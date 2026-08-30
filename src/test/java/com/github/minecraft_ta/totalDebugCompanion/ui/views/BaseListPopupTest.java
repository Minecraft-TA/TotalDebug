package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaseListPopupTest {

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
            popup.setItems(java.util.List.of(item));
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

    private static final class TestItem implements BaseListPopup.ListItem {
        @Override
        public int getLabelLength() {
            return 4;
        }
    }
}
