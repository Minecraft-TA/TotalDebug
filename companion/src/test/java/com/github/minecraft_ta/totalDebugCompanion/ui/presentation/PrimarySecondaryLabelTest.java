package com.github.minecraft_ta.totalDebugCompanion.ui.presentation;

import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;

import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimarySecondaryLabelTest {
    @Test
    void resolvesColorsAgainWheneverTheSameComponentIsPainted() throws Exception {
        PrimarySecondaryLabel label = new PrimarySecondaryLabel();
        label.configure(
                new PrimarySecondaryText("Primary", "Secondary"),
                null,
                UIManager.getFont("Label.font"),
                false,
                null
        );

        ThemeManager.installTheme(CompanionTheme.ISLANDS_DARK);
        paint(label);
        List<JLabel> children = labels(label);
        Color darkPrimary = children.get(0).getForeground();
        Color darkSecondary = children.get(1).getForeground();
        assertEquals(ThemeColors.text(), darkPrimary);
        assertEquals(ThemeColors.mutedText(), darkSecondary);

        ThemeManager.installTheme(CompanionTheme.ISLANDS_LIGHT);
        paint(label);
        assertEquals(ThemeColors.text(), children.get(0).getForeground());
        assertEquals(ThemeColors.mutedText(), children.get(1).getForeground());
        assertNotEquals(darkPrimary, children.get(0).getForeground());
        assertNotEquals(darkSecondary, children.get(1).getForeground());

        ThemeManager.installTheme(CompanionTheme.DEFAULT);
    }

    @Test
    void selectedRowsKeepAnOpaqueSearchMatchHighlight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                ThemeManager.installTheme(CompanionTheme.ISLANDS_DARK);
                JList<String> owner = new JList<>(new String[]{"Gradle"});
                SpeedSearch search = SpeedSearch.install(owner, value -> value);
                for (char character : "grad".toCharArray()) {
                    KeyEvent event = new KeyEvent(
                            owner,
                            KeyEvent.KEY_TYPED,
                            1L,
                            0,
                            KeyEvent.VK_UNDEFINED,
                            character
                    );
                    for (KeyListener listener : owner.getKeyListeners()) {
                        listener.keyTyped(event);
                    }
                }

                PrimarySecondaryLabel label = new PrimarySecondaryLabel();
                label.configure(
                        PrimarySecondaryText.primary("Gradle"),
                        null,
                        UIManager.getFont("Label.font"),
                        true,
                        UIManager.getColor("Tree.selectionForeground"),
                        UIManager.getColor("Tree.selectionBackground"),
                        owner
                );
                BufferedImage image = paintImage(label);
                Color searchMatch = ThemeColors.searchMatch();
                assertTrue(containsOpaqueColor(image, searchMatch));
                search.close();
            } finally {
                ThemeManager.installTheme(CompanionTheme.DEFAULT);
            }
        });
    }

    private static List<JLabel> labels(PrimarySecondaryLabel label) {
        return Arrays.stream(label.getComponents())
                .filter(JLabel.class::isInstance)
                .map(JLabel.class::cast)
                .toList();
    }

    private static void paint(PrimarySecondaryLabel label) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            paintImage(label);
        });
    }

    private static BufferedImage paintImage(PrimarySecondaryLabel label) {
        label.setSize(label.getPreferredSize());
        label.doLayout();
        BufferedImage image = new BufferedImage(
                Math.max(1, label.getWidth()),
                Math.max(1, label.getHeight()),
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = image.createGraphics();
        label.paint(graphics);
        graphics.dispose();
        return image;
    }

    private static boolean containsOpaqueColor(BufferedImage image, Color expected) {
        int rgb = expected.getRGB();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) == rgb) {
                    return true;
                }
            }
        }
        return false;
    }
}
