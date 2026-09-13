package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApplication;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.ProjectDirectories;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyFileJTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class OfflineProjectIntegrationTest {
    @TempDir Path root;

    @Test void anUnwritableCacheDoesNotPreventOpeningTheModCatalog() throws Exception {
        Path game = Files.createDirectories(root.resolve("instance"));
        Path jar = Files.createDirectory(game.resolve("mods")).resolve("demo.jar");
        writeProjectJar(jar, 42);
        var paths = InstancePaths.forGame(game);
        Files.createDirectories(paths.cache());
        Files.writeString(paths.runtime(), "existing file prevents cache-directory creation");
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(root.resolve("application")), "test")) {
            app.openProject(ProjectDirectories.resolve(game)).get(10, TimeUnit.SECONDS);
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.FAILED);
            assertEquals(1, app.requireProject().sources().modules().size());
            assertNull(app.requireProject().runtime());
            assertFalse(Files.exists(paths.scripts()));
            assertEquals("existing file prevents cache-directory creation", Files.readString(paths.runtime()));
        }
    }

    @Test void browsesBeforeIndexingThenRescansChangedSources() throws Exception {
        Path game = Files.createDirectories(root.resolve("Demo instance/minecraft"));
        Path jar = Files.createDirectory(game.resolve("mods")).resolve("demo.jar");
        writeProjectJar(jar, 42);
        Path home = root.resolve("application");
        GlobalConfig.getInstance().loadFrom(home);
        var paths = InstancePaths.forGame(game);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocker = CompletableFuture.runAsync(() -> {
            try {
                CacheFiles.locked(paths.runtime(), () -> {
                    locked.countDown();
                    assertTrue(release.await(30, TimeUnit.SECONDS));
                    return null;
                });
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        var window = new AtomicReference<MainWindow>();
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test")) {
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            app.openProject(ProjectDirectories.resolve(game)).get(10, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
                ThemeManager.installTheme(CompanionTheme.ISLANDS_DARK);
                ((AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance()).putMapping(
                        RSyntaxTextArea.SYNTAX_STYLE_JAVA, CustomJavaTokenMaker.class.getName());
                var view = app.createWindow();
                view.setFocusableWindowState(false);
                view.setBounds(-20000, -20000, 1280, 720);
                view.setVisible(true);
                window.set(view);
            });
            var view = window.get();
            view.navigation().navigate(new NavigationTarget.ArchiveEntry(jar, "assets/demo/textures/block/panel.png"),
                    NavigationService.Activation.KEEP_CURRENT_WINDOW).get(10, TimeUnit.SECONDS);
            view.navigation().navigate(new NavigationTarget.ArchiveEntry(jar, "assets/demo/lang/en_us.json"),
                    NavigationService.Activation.KEEP_CURRENT_WINDOW).get(10, TimeUnit.SECONDS);
            await(() -> onEdtText(view).contains("Copper Panel"));
            assertNull(app.requireProject().runtime());
            expandMods(view);
            captureThemes(view, "offline-browsing");
            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.READY);
            SwingUtilities.invokeAndWait(() -> assertEquals(2, view.getEditorTabs().getTabCount(), "Indexing must preserve opened mod resources"));
            assertEquals(IndexIdentity.Kind.LOCAL, app.indexSourceKind());
            assertFalse(Files.exists(paths.inventory()));
            assertFalse(Files.exists(paths.scripts()));
            view.navigation().navigate(new NavigationTarget.RuntimeClass("demo.Example"),
                    NavigationService.Activation.KEEP_CURRENT_WINDOW).get(15, TimeUnit.SECONDS);
            await(() -> onEdtText(view).contains("return 42"));
            expandMods(view);
            captureThemes(view, "offline-indexed");

            var previous = app.requireProject().requireRuntime();
            var oldIndex = previous.snapshot().index();
            writeProjectJar(jar, 84);
            Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() + 2000));
            assertThrows(Exception.class, () -> previous.decompiler().load("demo.Example").get(10, TimeUnit.SECONDS));
            await(() -> app.requireProject().runtime() != null && app.requireProject().runtime() != previous
                    && app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.READY);
            assertTrue(oldIndex.isDestroyed());
            var updated = app.requireProject().requireRuntime().decompiler().load("demo.Example").get(15, TimeUnit.SECONDS);
            assertTrue(Files.readString(updated.path()).contains("return 84"));
        } finally {
            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> { if (window.get() != null) window.get().dispose(); });
        }
    }

    private static void expandMods(MainWindow view) throws Exception {
        var tree = new AtomicReference<LazyFileJTree>();
        SwingUtilities.invokeAndWait(() -> tree.set(find(view, LazyFileJTree.class)));
        assertTrue(tree.get().revealDirectoryPath("runtime", "demo.jar [demo.jar]", List.of("demo")).get(5, TimeUnit.SECONDS));
    }

    private static void captureThemes(MainWindow view, String name) throws Exception {
        Path screenshots = Files.createDirectories(Path.of("build/ui-screenshots"));
        for (var theme : List.of(CompanionTheme.ISLANDS_DARK, CompanionTheme.ISLANDS_LIGHT)) {
            SwingUtilities.invokeAndWait(() -> ThemeManager.apply(theme));
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(() -> {
                view.validate();
                var image = new BufferedImage(view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                view.paintAll(graphics);
                graphics.dispose();
                assertDoesNotThrow(() -> ImageIO.write(image, "png", screenshots.resolve(name + (theme.dark() ? "-dark.png" : "-light.png")).toFile()));
            });
        }
    }

    private static String onEdtText(Container container) {
        var result = new AtomicReference<String>("");
        try { SwingUtilities.invokeAndWait(() -> {
            Container selected = container instanceof MainWindow window
                    ? (Container) window.getEditorTabs().getSelectedEditor().getComponent() : container;
            var editor = find(selected, RSyntaxTextArea.class);
            if (editor != null) result.set(editor.getText());
        }); } catch (Exception failure) { throw new AssertionError(failure); }
        return result.get();
    }

    private static <T> T find(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T result = find(nested, type);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "Offline project did not reach the expected state");
    }

    private static void writeProjectJar(Path jar, int value) throws Exception {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(162, 94, 57));
        graphics.fillRect(0, 0, 16, 16);
        graphics.setColor(new Color(211, 151, 97));
        graphics.drawRect(1, 1, 13, 13);
        graphics.dispose();
        var png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        try (var archive = new JarOutputStream(Files.newOutputStream(jar))) {
            archive.putNextEntry(new JarEntry("demo/Example.class"));
            archive.write(LocalIndexTest.classBytes("demo/Example", value));
            archive.closeEntry();
            archive.putNextEntry(new JarEntry("assets/demo/lang/en_us.json"));
            archive.write("{\n  \"block.demo.panel\": \"Copper Panel\"\n}\n".getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
            archive.putNextEntry(new JarEntry("assets/demo/textures/block/panel.png"));
            archive.write(png.toByteArray());
        }
    }
}
