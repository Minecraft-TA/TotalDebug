package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.formdev.flatlaf.util.UIScale;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ThinSplitPaneTest {
    @Test void bothEdgesDragWhileOnlyTheCenterLineIsPainted() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (CompanionTheme theme : CompanionTheme.available()) {
                ThemeManager.installTheme(theme);
                JPanel left = new JPanel();
                JPanel right = new JPanel();
                left.setMinimumSize(new Dimension(80, 0));
                right.setMinimumSize(new Dimension(90, 0));
                ThinSplitPane split = new ThinSplitPane(left, right);
                JFrame window = new JFrame();
                window.setAutoRequestFocus(false);
                window.setContentPane(split);
                window.setBounds(-20000, -20000, 600, 360);
                try {
                    window.setVisible(true);
                    split.setDividerLocation(220);
                    window.validate();
                    BasicSplitPaneDivider divider = ((BasicSplitPaneUI) split.getUI()).getDivider();
                    assertEquals(UIScale.scale(1), divider.getWidth());
                    assertEquals(divider.getWidth(), right.getX() - left.getX() - left.getWidth(),
                            "The wider mouse target must not reserve extra layout space");
                    assertEquals(Cursor.E_RESIZE_CURSOR, divider.getCursor().getType());
                    int margin = UIScale.scale(3);
                    for (int edge : new int[]{-margin, 0, divider.getWidth() + margin - 1}) {
                        split.setDividerLocation(220);
                        window.validate();
                        assertSame(divider, SwingUtilities.getDeepestComponentAt(split,
                                divider.getX() + edge, divider.getY() + 50));
                        drag(divider, edge, 40);
                        window.validate();
                        assertEquals(260, split.getDividerLocation(), "Dragging from either edge must move the split");
                    }
                    assertSame(left, SwingUtilities.getDeepestComponentAt(split, divider.getX() - margin - 1, 50));
                    assertSame(right, SwingUtilities.getDeepestComponentAt(split, divider.getX() + divider.getWidth() + margin, 50));
                    split.setEnabled(false);
                    assertSame(left, SwingUtilities.getDeepestComponentAt(split, divider.getX() - 1, 50));
                    split.setEnabled(true);
                    drag(divider, 0, -1000);
                    window.validate();
                    assertTrue(left.getWidth() >= left.getMinimumSize().width);
                    drag(divider, divider.getWidth() - 1, 1000);
                    window.validate();
                    assertTrue(right.getWidth() >= right.getMinimumSize().width);
                    split.setDividerLocation(220);
                    window.validate();
                    BufferedImage image = paint(split);
                    int y = split.getHeight() / 2;
                    int coloredColumns = 0;
                    for (int x = divider.getX(); x < divider.getX() + divider.getWidth(); x++) {
                        if (image.getRGB(x, y) == ThemeColors.separator().getRGB()) coloredColumns++;
                    }
                    assertEquals(1, coloredColumns, "The wider drag target must not become a thick separator");
                    Path output = Path.of("build/divider-preview", theme.id() + ".png");
                    try { Files.createDirectories(output.getParent()); ImageIO.write(image, "png", output.toFile()); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                    ThemeManager.installTheme(theme.dark() ? CompanionTheme.ISLANDS_LIGHT : CompanionTheme.ISLANDS_DARK);
                    SwingUtilities.updateComponentTreeUI(window);
                    window.validate();
                    divider = ((BasicSplitPaneUI) split.getUI()).getDivider();
                    assertEquals(UIScale.scale(1), divider.getWidth());
                    split.setDividerLocation(220);
                    window.validate();
                    drag(divider, -margin, 30);
                    assertEquals(250, split.getDividerLocation(), "Dragging must survive a theme change");
                } finally { window.dispose(); }
            }
        });
    }

    private static void drag(BasicSplitPaneDivider divider, int x, int distance) {
        JSplitPane split = (JSplitPane) divider.getParent();
        int before = split.getDividerLocation();
        divider.dispatchEvent(new MouseEvent(divider, MouseEvent.MOUSE_PRESSED, 0,
                InputEvent.BUTTON1_DOWN_MASK, x, 50, 1, false, MouseEvent.BUTTON1));
        assertEquals(before, split.getDividerLocation(), "Pressing beside the divider must not make it jump");
        divider.dispatchEvent(new MouseEvent(divider, MouseEvent.MOUSE_DRAGGED, 1,
                InputEvent.BUTTON1_DOWN_MASK, x + distance, 50, 0, false, MouseEvent.NOBUTTON));
        divider.getParent().doLayout();
        divider.dispatchEvent(new MouseEvent(divider, MouseEvent.MOUSE_RELEASED, 2,
                0, x, 50, 1, false, MouseEvent.BUTTON1));
    }

    private static BufferedImage paint(ThinSplitPane split) {
        BufferedImage image = new BufferedImage(split.getWidth(), split.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        split.printAll(graphics);
        graphics.dispose();
        return image;
    }
}
