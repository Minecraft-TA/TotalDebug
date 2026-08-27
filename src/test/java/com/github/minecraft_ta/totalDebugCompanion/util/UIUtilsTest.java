package com.github.minecraft_ta.totalDebugCompanion.util;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class UIUtilsTest {
    @Test
    void placesNavigationTargetsInTheUpperThirdOfTheViewport() throws Exception {
        AtomicReference<Double> targetPosition = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            StringBuilder source = new StringBuilder();
            for (int line = 1; line <= 160; line++) {
                source.append("line ").append(line).append('\n');
            }

            RSyntaxTextArea editor = new RSyntaxTextArea(source.toString());
            RTextScrollPane scrollPane = new RTextScrollPane(editor);
            scrollPane.setSize(new Dimension(640, 300));
            scrollPane.doLayout();
            editor.setSize(editor.getPreferredSize());
            scrollPane.getViewport().setViewSize(editor.getSize());

            int target = source.indexOf("line 100");
            UIUtils.positionViewportOnRange(scrollPane, target, target);

            try {
                Rectangle2D bounds = editor.modelToView2D(target);
                int viewportTop = scrollPane.getViewport().getViewPosition().y;
                int viewportHeight = scrollPane.getViewport().getExtentSize().height;
                targetPosition.set((bounds.getY() - viewportTop) / viewportHeight);
            } catch (javax.swing.text.BadLocationException exception) {
                throw new AssertionError(exception);
            }
        });

        assertTrue(targetPosition.get() >= 0.27 && targetPosition.get() <= 0.38,
                () -> "Expected the target in the upper third, but it was at " + targetPosition.get());
    }
}
