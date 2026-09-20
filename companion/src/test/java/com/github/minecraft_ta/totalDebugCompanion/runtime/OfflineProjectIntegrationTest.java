package com.github.minecraft_ta.totalDebugCompanion.runtime;

import javax.swing.JButton;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApplication;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.ProjectDirectories;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyFileJTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.SearchEverywherePopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.ApplicationStatusBar;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RuntimeInventoryMessage;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.JLabel;
import java.awt.Window;
import java.util.Arrays;
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
            assertEquals(IndexIdentity.Kind.LOCAL, app.getRuntimeIndexStatus().sourceKind());
            assertEquals(1, app.requireProject().sources().modules().size());
            assertNull(app.requireProject().runtime());
            assertFalse(Files.exists(paths.scripts()));
            assertEquals("existing file prevents cache-directory creation", Files.readString(paths.runtime()));
            Files.delete(paths.runtime());
            SwingUtilities.invokeAndWait(() -> {
                var view = app.createWindow();
                var bar = find(view, ApplicationStatusBar.class);
                try {
                    var retry = ApplicationStatusBar.class.getDeclaredField("retry");
                    retry.setAccessible(true);
                    ((JButton) retry.get(bar)).doClick();
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.READY);
            assertEquals(IndexIdentity.Kind.LOCAL, app.indexSourceKind());
        }
    }

    @Test void failedLocalRefreshRetiresItsIndexAndExposesTheNewCatalog() throws Exception {
        Path game = Files.createDirectories(root.resolve("refresh"));
        Path mods = Files.createDirectory(game.resolve("mods"));
        writeProjectJar(mods.resolve("old.jar"), 42);
        var paths = InstancePaths.forGame(game);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(root.resolve("application")), "test")) {
            var profile = ProjectDirectories.resolve(game);
            app.openProject(profile).get(10, TimeUnit.SECONDS);
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.READY);
            var previous = app.requireProject().requireRuntime().snapshot().index();
            Files.delete(mods.resolve("old.jar"));
            writeProjectJar(mods.resolve("new.jar"), 84);
            Files.delete(paths.index());
            Files.createDirectory(paths.index());
            Files.writeString(paths.index().resolve("blocker"), "prevents replacing the index");
            app.openProject(profile).get(10, TimeUnit.SECONDS);
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.FAILED);
            assertNull(app.requireProject().runtime(), "A failed local refresh must retire stale code sources");
            assertTrue(previous.isDestroyed());
            assertEquals(List.of("new.jar"), app.requireProject().sources().modules().stream()
                    .map(module -> module.displayName()).toList());
        }
    }

    @Test void emptyProjectSearchShowsNoSourcesInsteadOfBuildingForever() throws Exception {
        Path game = Files.createDirectories(root.resolve("empty"));
        Files.createDirectory(game.resolve("mods"));
        GlobalConfig.getInstance().loadFrom(root.resolve("application"));
        var popup = new AtomicReference<SearchEverywherePopup>();
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(root.resolve("application")), "test")) {
            app.openProject(ProjectDirectories.resolve(game)).get(10, TimeUnit.SECONDS);
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.EMPTY);
            SwingUtilities.invokeAndWait(() -> {
                ThemeManager.installTheme(CompanionTheme.ISLANDS_DARK);
                var view = app.createWindow();
                view.setFocusableWindowState(false);
                view.setBounds(-20000, -20000, 1280, 720);
                view.setVisible(true);
                view.openSearchEverywhere();
                popup.set(Arrays.stream(Window.getWindows()).filter(SearchEverywherePopup.class::isInstance)
                        .map(SearchEverywherePopup.class::cast).filter(Window::isShowing).findFirst().orElseThrow());
                popup.get().setFocusableWindowState(false);
            });
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var message = SearchEverywherePopup.class.getDeclaredField("messageLabel");
                    message.setAccessible(true);
                    assertEquals("No mod archives found", ((JLabel) message.get(popup.get())).getText());
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            captureThemes(popup.get(), "offline-empty-search");
        } finally { SwingUtilities.invokeAndWait(() -> { if (popup.get() != null) popup.get().dispose(); }); }
    }

    @Test void emptyIndexCanBeRescannedAfterAddingAMod() throws Exception {
        Path game = Files.createDirectories(root.resolve("empty-refresh"));
        Path mods = Files.createDirectory(game.resolve("mods"));
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(root.resolve("application")), "test")) {
            app.openProject(ProjectDirectories.resolve(game)).get(10, TimeUnit.SECONDS);
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.EMPTY);
            writeProjectJar(mods.resolve("demo.jar"), 42);
            app.retryIndex().get(10, TimeUnit.SECONDS);
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.READY);
            assertNotNull(app.requireProject().requireRuntime().snapshot().index().findClass("demo", "Example"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedRebuildKeepsTheInstalledRuntimeUsable(boolean runtime) throws Exception {
        Path game = Files.createDirectories(root.resolve("failed-refresh"));
        Path mods = Files.createDirectory(game.resolve("mods"));
        Path jar = (runtime ? root : mods).resolve("demo.jar");
        writeProjectJar(jar, 42);
        if (runtime) {
            var module = new RuntimeInventory.RuntimeModule("demo", "Demo", RuntimeInventory.ModuleKind.MOD);
            new RuntimeInventory("runtime-refresh", "21", System.getProperty("java.home"), true,
                    List.of(new RuntimeInventory.Source(RuntimeInventory.SourceKind.ARCHIVE, jar, jar.toUri().toString(), module)))
                    .write(InstancePaths.forGame(game).inventory());
        }
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(root.resolve("application")), "test")) {
            app.openProject(ProjectDirectories.resolve(game)).get(10, TimeUnit.SECONDS);
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.READY);
            var previous = app.requireProject().requireRuntime();
            assertEquals(runtime, previous.snapshot().isRuntime());
            Path index = previous.snapshot().indexFile();
            Files.move(index, index.resolveSibling("previous.jindex"));
            Files.createDirectory(index);
            Files.writeString(index.resolve("blocker"), "Prevent atomic cache replacement");
            app.retryIndex().get(10, TimeUnit.SECONDS);
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.FAILED);
            assertSame(previous, app.requireProject().runtime());
            assertNotNull(previous.snapshot().index().findClass("demo", "Example"));
        }
    }

    @Test void readyIndexRefreshReplacesTheRuntimeAndRebuildsItsCache() throws Exception {
        Path game = Files.createDirectories(root.resolve("refresh"));
        Path mods = Files.createDirectory(game.resolve("mods"));
        writeProjectJar(mods.resolve("demo.jar"), 42);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(root.resolve("application")), "test")) {
            app.openProject(ProjectDirectories.resolve(game)).get(10, TimeUnit.SECONDS);
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.READY);
            var previous = app.requireProject().requireRuntime();
            var directorySource = Source.capture(app.requireProject(), "Scripts", new NavigationTarget.LocalDirectory(game));
            assertNull(directorySource.runtimeSignature());
            app.retryIndex().get(10, TimeUnit.SECONDS);
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.READY);
            var replacement = app.requireProject().requireRuntime();
            assertNotSame(previous, replacement);
            assertEquals(previous.snapshot().identity(), replacement.snapshot().identity());
            assertTrue(app.getRuntimeIndexStatus().metrics().rebuilt());
            assertNotNull(replacement.snapshot().index().findClass("demo", "Example"));
        }
    }

    @Test void failedRuntimeHandoverPreservesLocalBrowsingAndCanBeRetriedOffline() throws Exception {
        Path game = Files.createDirectories(root.resolve("handover"));
        Path mods = Files.createDirectory(game.resolve("mods"));
        writeProjectJar(mods.resolve("demo.jar"), 42);
        var paths = InstancePaths.forGame(game);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(root.resolve("application")), "test")) {
            app.openProject(ProjectDirectories.resolve(game)).get(10, TimeUnit.SECONDS);
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.READY);
            var previous = app.requireProject().requireRuntime();
            Files.writeString(paths.inventory(), "invalid runtime capture");
            var receive = CompanionApplication.class.getDeclaredMethod("handleRuntimeInventory", RuntimeInventoryMessage.class);
            receive.setAccessible(true);
            receive.invoke(app, RuntimeInventoryMessage.available("invalid", paths.inventory().toString()));
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.FAILED);
            assertEquals(IndexIdentity.Kind.RUNTIME, app.getRuntimeIndexStatus().sourceKind());
            assertSame(previous, app.requireProject().runtime());
            assertNotNull(previous.snapshot().index().findClass("demo", "Example"));
            receive.invoke(app, RuntimeInventoryMessage.failed("Capture failed"));
            assertSame(previous, app.requireProject().runtime());
            app.retryIndex().get(10, TimeUnit.SECONDS);
            await(() -> app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.READY);
            assertEquals(IndexIdentity.Kind.LOCAL, app.indexSourceKind());
            assertSame(previous, app.requireProject().runtime());
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
        assertTrue(tree.get().revealItemPath("runtime", "demo.jar [demo.jar]", List.of("demo")).get(5, TimeUnit.SECONDS));
    }

    private static void captureThemes(Window view, String name) throws Exception {
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
