package com.github.minecraft_ta.totalDebugCompanion.ui.presentation;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

    private static List<JLabel> labels(PrimarySecondaryLabel label) {
        return Arrays.stream(label.getComponents())
                .filter(JLabel.class::isInstance)
                .map(JLabel.class::cast)
                .toList();
    }

    private static void paint(PrimarySecondaryLabel label) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
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
        });
    }
}
