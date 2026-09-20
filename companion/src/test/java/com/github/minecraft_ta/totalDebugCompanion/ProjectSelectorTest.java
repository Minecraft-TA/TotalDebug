package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectControls;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.runtime.IndexIdentity;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.ProjectRegistry;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.ProjectSelector;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.imageio.ImageIO;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProjectSelectorTest {
    @Test void rightClickShowsManagementWithoutOpeningTheProject() throws Exception {
        for (var theme : List.of(CompanionTheme.ISLANDS_DARK, CompanionTheme.ISLANDS_LIGHT)) {
            SwingUtilities.invokeAndWait(() -> {
                ThemeManager.installTheme(theme);
                var opened = new AtomicInteger();
                var forgotten = new AtomicReference<String>();
                var profile = CompanionProfile.forGame(Path.of("test-instance"));
                var controls = new ProjectControls() {
                    public List<ProjectRegistry.Project> projects() { return List.of(new ProjectRegistry.Project("Named pack", profile)); }
                    public CompanionProfile currentProject() { return null; }
                    public boolean isSwitching() { return false; }
                    public boolean isConnected() { return false; }
                    public IndexIdentity.Kind indexSourceKind() { return null; }
                    public CompletableFuture<Void> retryIndex() { throw new AssertionError(); }
                    public RuntimeIndexService.Status getRuntimeIndexStatus() { throw new AssertionError(); }
                    public CompletableFuture<Void> openProject(CompanionProfile selected, String name) {
                        opened.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    }
                    public CompletableFuture<Void> renameProject(String id, String name) { throw new AssertionError(); }
                    public CompletableFuture<Void> forgetProject(String id) {
                        forgotten.set(id);
                        return CompletableFuture.completedFuture(null);
                    }
                };
                var owner = new JFrame();
                var selector = new ProjectSelector(controls, new NotificationCenter());
                var previousFactory = PopupFactory.getSharedInstance();
                try {
                    PopupFactory.setSharedInstance(new OffscreenPopupFactory());
                    owner.setFocusableWindowState(false);
                    owner.setBounds(-20000, -20000, 800, 600);
                    var bar = new JMenuBar();
                    bar.add(selector);
                    owner.setJMenuBar(bar);
                    owner.setVisible(true);
                    OffscreenPopupFactory.expectAt(selector, new Point(0, selector.getHeight()));
                    selector.doClick(0);
                    var row = selector.getItem(selector.getItemCount() - 1);
                    MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{bar, selector, selector.getPopupMenu(), row});
                    OffscreenPopupFactory.expectAt(selector, new Point(15, selector.getHeight() + 15));
                    row.dispatchEvent(new MouseEvent(row, MouseEvent.MOUSE_PRESSED, 1, 0, 15, 15, 1, false, MouseEvent.BUTTON3));
                    row.dispatchEvent(new MouseEvent(row, MouseEvent.MOUSE_RELEASED, 2, 0, 15, 15, 1, true, MouseEvent.BUTTON3));
                    assertEquals(0, opened.get());
                    var options = row.getComponentPopupMenu();
                    assertTrue(options.isVisible());
                    assertEquals("Rename…", ((JMenuItem) options.getComponent(0)).getText());
                    assertEquals("Reset to automatic name", ((JMenuItem) options.getComponent(1)).getText());
                    assertDoesNotThrow(() -> {
                        var image = new BufferedImage(owner.getWidth(), owner.getHeight(), BufferedImage.TYPE_INT_RGB);
                        var graphics = image.createGraphics();
                        owner.paintAll(graphics);
                        OffscreenPopupFactory.paintActivePopups(graphics, owner);
                        graphics.dispose();
                        Path screenshots = Files.createDirectories(Path.of("build/ui-screenshots"));
                        ImageIO.write(image, "png", screenshots.resolve("project-options-" + (theme.dark() ? "dark" : "light") + ".png").toFile());
                    });
                    ((JMenuItem) options.getComponent(2)).doClick(0);
                    assertEquals(profile.id(), forgotten.get());
                    assertEquals(0, opened.get());
                } finally {
                    MenuSelectionManager.defaultManager().clearSelectedPath();
                    selector.dispose();
                    owner.dispose();
                    PopupFactory.setSharedInstance(previousFactory);
                }
            });
        }
    }
}
