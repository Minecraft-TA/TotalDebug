package com.github.minecraft_ta.totalDebugCompanion.ui.theme;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.swing.JScrollBar;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ScrollBarRepaintTest {
    private static final int WIDTH = 14;
    private static final int HEIGHT = 180;

    static java.util.List<CompanionTheme> themes() {
        return CompanionTheme.available();
    }

    @ParameterizedTest
    @MethodSource("themes")
    void movingTheThumbClearsItsPreviousPixels(CompanionTheme theme) {
        ThemeManager.installTheme(theme);

        JScrollBar scrollBar = new JScrollBar(JScrollBar.VERTICAL, 0, 20, 0, 100);
        scrollBar.setSize(WIDTH, HEIGHT);
        scrollBar.doLayout();

        BufferedImage repainted = blankCanvas(UIManager.getColor("Panel.background"));
        paint(scrollBar, repainted);
        scrollBar.setValue(65);
        paint(scrollBar, repainted);

        BufferedImage freshlyPainted = blankCanvas(UIManager.getColor("Panel.background"));
        paint(scrollBar, freshlyPainted);

        assertArrayEquals(
                pixels(freshlyPainted),
                pixels(repainted),
                theme.id() + " must clear the old thumb before drawing its new position"
        );
    }

    private static BufferedImage blankCanvas(Color background) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(background);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.dispose();
        return image;
    }

    private static void paint(JScrollBar scrollBar, BufferedImage image) {
        Graphics2D graphics = image.createGraphics();
        scrollBar.paint(graphics);
        graphics.dispose();
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
