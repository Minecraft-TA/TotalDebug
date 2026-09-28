package com.github.minecraft_ta.totalDebugCompanion.ui.components.subject;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkLabelTest {
    @Test
    void aLinkOpensWithEnterOrSpaceWhileFocused() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            LinkLabel link = new LinkLabel("World", null, "Show in Explorer", opened::incrementAndGet);
            assertTrue(link.isFocusable(), "Tab reaches the link");
            for (String key : new String[]{"ENTER", "SPACE"}) {
                Object action = link.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key));
                link.getActionMap().get(action).actionPerformed(new ActionEvent(link, ActionEvent.ACTION_PERFORMED, key));
            }
        });
        assertEquals(2, opened.get());
    }
}
