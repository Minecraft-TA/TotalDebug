package com.github.minecraft_ta.totalDebugCompanion.ui.theme;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The icon constants in {@link Icons} are shared singletons created once at class-init. Light/dark
 * switching therefore only works if a FlatSVGIcon re-resolves its {@code _dark.svg} sibling when the
 * look and feel changes - if it cached the first one, every icon would stay stuck on whichever theme
 * happened to load first.
 */
class IconThemeSwitchTest {

    private static BufferedImage render(FlatSVGIcon icon) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        icon.paintIcon(new JPanel(), g, 0, 0);
        g.dispose();
        return image;
    }

    private static BufferedImage render(Image source) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.drawImage(source, 0, 0, 16, 16, null);
        g.dispose();
        return image;
    }

    private static long checksum(BufferedImage image) {
        long sum = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                sum = sum * 31 + image.getRGB(x, y);
            }
        }
        return sum;
    }

    @Test
    void sharedIconInstanceFollowsTheTheme() {
        FlatSVGIcon icon = new FlatSVGIcon("icons/class.svg");

        ThemeManager.installTheme(CompanionTheme.ISLANDS_LIGHT);
        long light = checksum(render(icon));

        ThemeManager.installTheme(CompanionTheme.ISLANDS_DARK);
        long dark = checksum(render(icon));

        assertNotEquals(light, dark,
                "the same FlatSVGIcon instance rendered identically in both themes - the _dark variant is not being picked up");
    }

    @Test
    void windowIconFactoryUsesTheThemeVariant() {
        long light = checksum(render(Icons.createWindowIconImages(CompanionTheme.ISLANDS_LIGHT).getFirst()));
        long dark = checksum(render(Icons.createWindowIconImages(CompanionTheme.ISLANDS_DARK).getFirst()));

        assertNotEquals(light, dark, "window icon factory rendered the light asset for both themes");
    }

    @Test
    void everyIconConstantResolvesToAResource() throws Exception {
        List<String> broken = new ArrayList<>();
        for (Field field : Icons.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != FlatSVGIcon.class) {
                continue;
            }
            FlatSVGIcon icon = (FlatSVGIcon) field.get(null);
            if (Icons.class.getClassLoader().getResource(icon.getName()) == null) {
                broken.add(field.getName() + " -> " + icon.getName());
            }
        }
        if (!broken.isEmpty()) {
            fail("Icon constants pointing at missing resources: " + broken);
        }
    }

    @Test
    void everyIconShipsADarkVariant() throws Exception {
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Field field : Icons.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != FlatSVGIcon.class) {
                continue;
            }
            FlatSVGIcon icon = (FlatSVGIcon) field.get(null);
            String name = icon.getName();
            String darkName = name.substring(0, name.length() - ".svg".length()) + "_dark.svg";
            if (Icons.class.getClassLoader().getResource(darkName) == null) {
                missing.add(darkName);
            }
            checked++;
        }
        assertTrue(checked > 30, "expected to check the whole icon set, only saw " + checked);
        if (!missing.isEmpty()) {
            fail("Icons without a dark variant (they will look wrong in one theme): " + missing);
        }
    }
}
