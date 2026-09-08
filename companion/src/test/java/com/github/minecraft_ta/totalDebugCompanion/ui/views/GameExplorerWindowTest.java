package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.GameCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class GameExplorerWindowTest {
    @TempDir Path directory;

    @Test void opensSourceWithoutClosingOverviewAndRendersCapturedResources() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var snapshot = new GameCatalogService().load(CatalogFixtures.create(directory));
        var reference = new AtomicReference<GameExplorerWindow>();
        var navigation = new ArrayList<NavigationTarget>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager.installTheme(
                        com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme.ISLANDS_DARK);
                var window = new GameExplorerWindow(null, snapshot, navigation::add);
                reference.set(window);
                window.addNotify();
                window.validate();
                components(window, JList.class).getFirst().setSelectedIndex(1);
            });
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            AtomicBoolean rendered = new AtomicBoolean();
            while (!rendered.get() && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> rendered.set(components(reference.get(), JLabel.class).stream()
                        .anyMatch(label -> label.getIcon() != null && label.getIcon().getIconWidth() == 256)));
                if (!rendered.get()) Thread.sleep(20);
            }
            assertTrue(rendered.get(), "The offline preview did not finish");
            SwingUtilities.invokeAndWait(() -> {
                var window = reference.get();
                components(window, JButton.class).stream().filter(button -> button.getText().equals("Open source"))
                        .findFirst().orElseThrow().doClick();
                assertEquals(1, navigation.size());
                assertInstanceOf(NavigationTarget.RuntimeClass.class, navigation.getFirst());
                assertTrue(window.isDisplayable(), "Source navigation must keep the inspector open");
                try {
                    Path imagePath = Path.of("build/ui-screenshots/game-explorer.png");
                    Files.createDirectories(imagePath.getParent());
                    var image = new BufferedImage(window.getContentPane().getWidth(), window.getContentPane().getHeight(), BufferedImage.TYPE_INT_ARGB);
                    var graphics = image.createGraphics();
                    window.getContentPane().printAll(graphics);
                    graphics.dispose();
                    ImageIO.write(image, "png", imagePath.toFile());
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (reference.get() != null) reference.get().dispose(); });
        }
    }

    private static <T extends Component> List<T> components(Component root, Class<T> type) {
        var result = new ArrayList<T>();
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof Container container)
            for (Component child : container.getComponents()) result.addAll(components(child, type));
        return result;
    }
}
