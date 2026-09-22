package com.github.minecraft_ta.totalDebugCompanion.ui.theme;

import com.formdev.flatlaf.ui.FlatTitlePane;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.SettingsWindow;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsWindowThemeTest {
    @TempDir Path directory;

    static List<CompanionTheme> themes() { return CompanionTheme.available(); }

    @ParameterizedTest @MethodSource("themes")
    void openSettingsIconFollowsItsThemeChooserAndReleasesItsListener(CompanionTheme initial) throws Exception {
        GlobalConfig.getInstance().loadFrom(directory);
        SwingUtilities.invokeAndWait(() -> {
            boolean decoratedFrames = JFrame.isDefaultLookAndFeelDecorated();
            boolean decoratedDialogs = JDialog.isDefaultLookAndFeelDecorated();
            JFrame.setDefaultLookAndFeelDecorated(true);
            JDialog.setDefaultLookAndFeelDecorated(true);
            ThemeManager.installTheme(initial);
            int baseline = ThemeManager.listenerCount();
            JFrame owner = new JFrame("Companion");
            Consumer<CompanionTheme> ownerListener = theme -> owner.setIconImages(Icons.createWindowIconImages(theme));
            ownerListener.accept(initial);
            ThemeManager.addThemeChangeListener(ownerListener);
            SettingsWindow settings = null;
            try {
                settings = new SettingsWindow(owner, InstanceState.inMemory(), null);
                assertIcon(settings, initial);
                var chooser = find(settings.getContentPane(), JComboBox.class);
                CompanionTheme other = initial.dark() ? CompanionTheme.ISLANDS_LIGHT : CompanionTheme.ISLANDS_DARK;
                chooser.setSelectedItem(other);
                assertIcon(settings, other);
                chooser.setSelectedItem(initial);
                assertIcon(settings, initial);
                ThemeManager.reapply();
                assertIcon(settings, initial);
                settings.dispose();
                settings.dispose();
                assertEquals(baseline + 1, ThemeManager.listenerCount(), "Only the owner's listener should remain");
                var reopened = new SettingsWindow(owner, InstanceState.inMemory(), null);
                try { assertIcon(reopened, initial); }
                finally { reopened.dispose(); }
            } finally {
                if (settings != null) settings.dispose();
                owner.dispose();
                ThemeManager.removeThemeChangeListener(ownerListener);
                JFrame.setDefaultLookAndFeelDecorated(decoratedFrames);
                JDialog.setDefaultLookAndFeelDecorated(decoratedDialogs);
            }
            assertEquals(baseline, ThemeManager.listenerCount());
        });
    }

    private static void assertIcon(SettingsWindow window, CompanionTheme theme) {
        var title = find(window.getRootPane(), FlatTitlePane.class);
        var label = iconLabel(title);
        assertEquals(pixels(new ImageIcon(Icons.createWindowIconImages(theme).getFirst())), pixels(label.getIcon()),
                "Settings title icon must follow " + theme.id());
    }

    private static long pixels(Icon icon) {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        icon.paintIcon(new JLabel(), graphics, 0, 0);
        graphics.dispose();
        long checksum = 0;
        for (int pixel : image.getRGB(0, 0, 16, 16, null, 0, 16)) checksum = checksum * 31 + pixel;
        return checksum;
    }

    private static JLabel iconLabel(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label && label.getIcon() != null) return label;
            if (child instanceof Container nested) {
                JLabel found = iconLabel(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T find(Container container, Class<T> type) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found = find(nested, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
