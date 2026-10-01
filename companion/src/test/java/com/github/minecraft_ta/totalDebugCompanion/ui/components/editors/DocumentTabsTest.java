package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApplication;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.model.ConfigFileView;
import com.github.minecraft_ta.totalDebugCompanion.model.ResourceView;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PageLoader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.ConfigFilePanel;
import com.github.minecraft_ta.totalDebugCompanion.util.Workers;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.lang.reflect.Field;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Document tabs (scripts, resources, local source files, configuration files) opened the way a click opens them: built by
 * the application, opened through navigation, shown in the window (docs/EDITOR_LOADING.md, finish line).
 */
@UiTest
class DocumentTabsTest {
    @TempDir Path directory;

    @BeforeAll
    static void registerTokenMakers() {
        CompanionApp.configureTokenMakers();
    }

    /** What a test does with the window of a project opened on {@code game}. */
    @FunctionalInterface
    private interface WithWindow {
        void run(CompanionApplication app, MainWindow window, Path game) throws Exception;
    }

    private void withWindow(WithWindow body) throws Exception {
        Path home = Files.createDirectory(this.directory.resolve("home"));
        GlobalConfig.getInstance().loadFrom(home);
        Path game = Files.createDirectory(this.directory.resolve("game"));
        try (CompanionApplication app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(game)).get(10, TimeUnit.SECONDS);
            MainWindow window = UiTestScope.onEdt(app::createWindow);
            UiTestScope.onEdt(() -> {
                window.setSize(1280, 720);
                UiTestScope.show(window);
            });
            body.run(app, window, game);
        }
    }

    @Test
    void anOffsetInALogTheGameWroteToSinceShowsInTheNewText() throws Exception {
        withWindow((app, window, game) -> {
            Path log = Files.writeString(Files.createDirectories(game.resolve("logs")).resolve("latest.log"), "first line\n");
            open(window, new NavigationTarget.LocalFile(log, 3));
            UiTestScope.await(() -> caret(window) == 3);

            // The game writes on while the tab is open.
            Files.writeString(log, "second line\n", StandardOpenOption.APPEND);
            Files.setLastModifiedTime(log, FileTime.from(Instant.now().plusSeconds(5)));
            open(window, new NavigationTarget.LocalFile(log, 15));
            UiTestScope.await(() -> caret(window) == 15);
            assertEquals("first line\nsecond line\n", UiTestScope.onEdt(() -> text(window)), "the offset is placed in what the file holds now");
        });
    }

    @Test
    void aFileThatNoLongerExistsFailsTheNavigationAndOpensNoTab() throws Exception {
        withWindow((app, window, game) -> {
            int tabs = UiTestScope.onEdt(() -> window.getEditorTabs().getTabCount());
            ExecutionException failed = assertThrows(ExecutionException.class, () -> window.navigation()
                    .navigate(new NavigationTarget.LocalFile(game.resolve("gone.txt")), NavigationService.Activation.KEEP_CURRENT_WINDOW)
                    .get(10, TimeUnit.SECONDS));
            assertTrue(failed.getCause().getMessage().contains("File does not exist"), failed.getCause().getMessage());
            settle();
            assertEquals(tabs, (int) UiTestScope.onEdt(() -> window.getEditorTabs().getTabCount()), "no tab opens");
        });
    }

    @Test
    void anOpenScriptIsFocusedWithoutBeingReadAgain() throws Exception {
        withWindow((app, window, game) -> {
            Path scripts = Files.createDirectories(app.currentScope().scriptFiles().root());
            Path script = Files.writeString(scripts.resolve("Draft.tdscript"), "return 1;");
            open(window, new NavigationTarget.LocalFile(script));
            ScriptView view = UiTestScope.onEdt(() -> assertInstanceOf(ScriptView.class, window.getEditorTabs().getSelectedEditor()));

            // Another program writes the file; the open tab keeps what it shows, and its save would check the change.
            Files.writeString(script, "return 3;");
            open(window, new NavigationTarget.Changes());
            open(window, new NavigationTarget.LocalFile(script));
            assertEquals(view, UiTestScope.onEdt(() -> window.getEditorTabs().getSelectedEditor()), "the open tab is focused");
            assertEquals("return 1;", UiTestScope.onEdt(view::currentText));
        });
    }

    @Test
    void backToAJavaFileRestoresItsCaret() throws Exception {
        withWindow((app, window, game) -> {
            Path java = Files.writeString(game.resolve("Sample.java"), "class Sample {\n    int value;\n    void run() { }\n}\n");
            open(window, new NavigationTarget.LocalFile(java, 20));
            assertInstanceOf(CodeView.class, UiTestScope.onEdt(() -> window.getEditorTabs().getSelectedEditor()));
            UiTestScope.await(() -> caret(window) == 20);

            open(window, new NavigationTarget.Changes());
            window.navigation().goBack().get(10, TimeUnit.SECONDS);
            settle();
            assertInstanceOf(CodeView.class, UiTestScope.onEdt(() -> window.getEditorTabs().getSelectedEditor()));
            UiTestScope.await(() -> caret(window) == 20);
        });
    }

    @Test
    void aResourceThatCannotBeCopiedYetOpensWithItsOriginalTextAndTheEditorsNotice() throws Exception {
        withWindow((app, window, game) -> {
            Path jar = CatalogFixtures.modJar(this.directory);
            String recipe = "data/testmod/recipe/widget.json";
            // No world exists yet, so a data resource has no pack to be copied into.
            open(window, new NavigationTarget.ArchiveEntry(jar, recipe));
            PackResourceEditor<?> editor = UiTestScope.onEdt(() ->
                    ((ResourceViewPanel) window.getEditorTabs().getSelectedEditor().getComponent()).editor());
            assertNotNull(editor, "a mod's data file opens in its editor");
            UiTestScope.await(() -> !editor.noticeText().isEmpty());
            assertEquals(recipe, UiTestScope.onEdt(() -> text(window)), "the editor shows the original");
        });
    }

    @Test
    void aScriptOpenedFromTheTreeIsReadOffTheSwingThread() throws Exception {
        withWindow((app, window, game) -> {
            Path scripts = Files.createDirectories(app.currentScope().scriptFiles().root());
            Path script = Files.writeString(scripts.resolve("Probe.tdscript"), "return 1;");
            Path capture = this.directory.resolve("script-open.jfr");
            try (var recording = new Recording()) {
                recording.enable("jdk.FileRead").withThreshold(Duration.ZERO);
                recording.start();
                open(window, new NavigationTarget.LocalFile(script));
                recording.stop();
                recording.dump(capture);
            }
            var reads = RecordingFile.readAllEvents(capture).stream()
                    .filter(event -> event.getEventType().getName().equals("jdk.FileRead"))
                    .filter(event -> script.toString().equals(event.getString("path"))).toList();
            assertFalse(reads.isEmpty(), "the probe observes the script being read");
            assertTrue(reads.stream().noneMatch(event -> event.getThread().getJavaName().startsWith("AWT-EventQueue")),
                    "opening a script does not read it on the Swing thread");
        });
    }

    @Test
    void aConfigurationFileOpenedAtAnOffsetShowsTheCaretThere() throws Exception {
        withWindow((app, window, game) -> {
            Path config = configured(app, game);
            open(window, new NavigationTarget.LocalFile(config, 30));
            assertInstanceOf(ConfigFileView.class, UiTestScope.onEdt(() -> window.getEditorTabs().getSelectedEditor()));
            UiTestScope.await(() -> text(window).startsWith("[widgets]") && caret(window) == 30);
        });
    }

    @Test
    void aResourceEditorKeepsUnsavedChangesWhenANavigationGoesToAnOffsetInIt() throws Exception {
        withWindow((app, window, game) -> {
            Path pack = Files.createDirectories(game.resolve("resourcepacks/mine"));
            Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"\"}}");
            Path lang = Files.writeString(Files.createDirectories(pack.resolve("assets/testmod/lang")).resolve("en_us.json"),
                    "{\"a\":\"b\"}");
            open(window, new NavigationTarget.LocalFile(lang));
            PackResourceEditor<?> editor = UiTestScope.onEdt(() ->
                    ((ResourceViewPanel) window.getEditorTabs().getSelectedEditor().getComponent()).editor());
            assertNotNull(editor, "a resource of a pack opens in its editor");
            editor.reading().get(5, TimeUnit.SECONDS);
            settle();
            UiTestScope.onEdt(() -> textArea(selected(window)).setText("{\"a\":\"mine\"}"));

            // Another program writes the file while the user edits it.
            Files.writeString(lang, "{\"a\":\"theirs\"}");
            Files.setLastModifiedTime(lang, FileTime.from(Instant.now().plusSeconds(5)));
            open(window, new NavigationTarget.LocalFile(lang, 3));
            assertEquals("{\"a\":\"mine\"}", UiTestScope.onEdt(() -> text(window)), "the user's changes stay");
            // Back to the text the editor read, so closing asks nothing.
            UiTestScope.onEdt(() -> textArea(selected(window)).setText("{\"a\":\"b\"}"));
        });
    }

    @Test
    void aLocalResourceIsReadBeforeItsTabExists() throws Exception {
        withWindow((app, window, game) -> {
            Path note = Files.writeString(game.resolve("note.txt"), "hello");
            int tabs = UiTestScope.onEdt(() -> window.getEditorTabs().getTabCount());
            CompletableFuture<Void> opening;
            try (var held = holdFileWorkers()) {
                opening = window.navigation().navigate(new NavigationTarget.LocalFile(note), NavigationService.Activation.KEEP_CURRENT_WINDOW);
                settle();
                assertEquals(tabs, (int) UiTestScope.onEdt(() -> window.getEditorTabs().getTabCount()),
                        "no tab exists while its read waits");
            }
            opening.get(10, TimeUnit.SECONDS);
            settle();
            assertEquals("hello", UiTestScope.onEdt(() -> text(window)));
        });
    }

    @Test
    void twoNavigationsInARowLeaveTheCaretAtTheSecond() throws Exception {
        withWindow((app, window, game) -> {
            Path note = Files.writeString(game.resolve("note.txt"), "0123456789\nabcdefghij\n");
            CompletableFuture<Void> first;
            CompletableFuture<Void> second;
            try (var held = holdFileWorkers()) {
                first = window.navigation().navigate(new NavigationTarget.LocalFile(note, 3), NavigationService.Activation.KEEP_CURRENT_WINDOW);
                settle();
                second = window.navigation().navigate(new NavigationTarget.LocalFile(note, 14), NavigationService.Activation.KEEP_CURRENT_WINDOW);
                settle();
            }
            second.get(10, TimeUnit.SECONDS);
            first.handle((ignored, failure) -> null).get(10, TimeUnit.SECONDS);
            settle();
            UiTestScope.await(() -> caret(window) == 14);
        });
    }

    @Test
    void aMovedResourcePreviewThatIsNotSelectedKeepsItsPosition() throws Exception {
        withWindow((app, window, game) -> {
            Path folder = Files.createDirectories(app.currentScope().scriptFiles().root().resolve("notes"));
            Path note = Files.writeString(folder.resolve("note.txt"), "0123456789\nabcdefghij\n");
            open(window, new NavigationTarget.LocalFile(note, 4));
            UiTestScope.await(() -> caret(window) == 4);
            open(window, new NavigationTarget.Changes());

            Path moved = folder.resolveSibling("moved");
            UiTestScope.onEdt(() -> window.scriptFileActions().rename(folder, moved)).get(10, TimeUnit.SECONDS);
            settle();
            Path relocated = moved.resolve("note.txt");
            UiTestScope.onEdt(() -> {
                var tabs = window.getEditorTabs();
                for (int index = 0; index < tabs.getTabCount(); index++) {
                    if (new NavigationTarget.LocalFile(relocated).equals(tabs.editors().get(index).getNavigationTarget())) {
                        tabs.setSelectedIndex(index);
                    }
                }
            });
            settle();
            assertInstanceOf(ResourceView.class, UiTestScope.onEdt(() -> window.getEditorTabs().getSelectedEditor()));
            UiTestScope.await(() -> caret(window) == 4);
        });
    }

    @Test
    void aClosedConfigurationTabDisposesItsLoader() throws Exception {
        withWindow((app, window, game) -> {
            Path config = configured(app, game);
            open(window, new NavigationTarget.LocalFile(config));
            ConfigFilePanel panel = UiTestScope.onEdt(() -> (ConfigFilePanel) window.getEditorTabs().getSelectedEditor().getComponent());
            UiTestScope.onEdt(() -> window.getEditorTabs().closeMatching(editor -> editor instanceof ConfigFileView));
            Field loaderField = ConfigFilePanel.class.getDeclaredField("loader");
            loaderField.setAccessible(true);
            Field disposed = PageLoader.class.getDeclaredField("disposed");
            disposed.setAccessible(true);
            Object loader = loaderField.get(panel);
            assertTrue(UiTestScope.onEdt(() -> disposed.getBoolean(loader)), "closing the tab disposes its loader");
        });
    }

    @Test
    void anOpenScriptWhoseFileWasDeletedIsFocused() throws Exception {
        withWindow((app, window, game) -> {
            Path scripts = Files.createDirectories(app.currentScope().scriptFiles().root());
            Path script = Files.writeString(scripts.resolve("Gone.tdscript"), "return 1;");
            open(window, new NavigationTarget.LocalFile(script));
            ScriptView view = UiTestScope.onEdt(() -> assertInstanceOf(ScriptView.class, window.getEditorTabs().getSelectedEditor()));
            Files.delete(script);
            open(window, new NavigationTarget.Changes());
            open(window, new NavigationTarget.LocalFile(script));
            assertEquals(view, UiTestScope.onEdt(() -> window.getEditorTabs().getSelectedEditor()), "the open tab is focused");
        });
    }

    @Test
    void aFileThatCannotBeReadFailsTheNavigationAndOpensNoTab() throws Exception {
        withWindow((app, window, game) -> {
            Path binary = Files.write(game.resolve("Sample.class"), new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            Path java = Files.write(game.resolve("Broken.java"), new byte[] {(byte) 0xFF, (byte) 0xFE, (byte) 0xC3, (byte) 0x28});
            int tabs = UiTestScope.onEdt(() -> window.getEditorTabs().getTabCount());
            for (Path file : List.of(binary, java)) {
                assertThrows(ExecutionException.class, () -> window.navigation()
                        .navigate(new NavigationTarget.LocalFile(file), NavigationService.Activation.KEEP_CURRENT_WINDOW)
                        .get(10, TimeUnit.SECONDS), file.getFileName() + " fails the navigation");
                settle();
                assertEquals(tabs, (int) UiTestScope.onEdt(() -> window.getEditorTabs().getTabCount()),
                        file.getFileName() + " opens no tab");
            }
        });
    }

    @Test
    void backToAResourceRestoresItsCaret() throws Exception {
        withWindow((app, window, game) -> {
            Path note = Files.writeString(game.resolve("note.txt"), "0123456789\nabcdefghij\n");
            open(window, new NavigationTarget.LocalFile(note, 5));
            UiTestScope.await(() -> caret(window) == 5);
            open(window, new NavigationTarget.LocalFile(note, 14));
            UiTestScope.await(() -> caret(window) == 14);
            window.navigation().goBack().get(10, TimeUnit.SECONDS);
            settle();
            UiTestScope.await(() -> caret(window) == 5);
        });
    }

    /** Writes a mod's configuration file and the catalog that names it; returns the file. */
    private Path configured(CompanionApplication app, Path game) throws Exception {
        ProjectScope scope = app.currentScope();
        CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)).write(scope.paths().catalog());
        scope.catalog().accept(CatalogFixtures.INVENTORY, scope.paths().catalog(), Runnable::run);
        return Files.writeString(Files.createDirectories(game.resolve("config")).resolve("testmod-common.toml"),
                "[widgets]\n# How fast widgets spin\nspeed = 4\nmode = \"FAST\"\n");
    }

    /** Fills every file worker with a task that waits until closed, so a read queued meanwhile waits too. */
    private static AutoCloseable holdFileWorkers() throws Exception {
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        for (int worker = 0; worker < 4; worker++) {
            Workers.files().execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(started.await(5, TimeUnit.SECONDS), "every file worker is held");
        return release::countDown;
    }

    private static void open(MainWindow window, NavigationTarget target) throws Exception {
        window.navigation().navigate(target, NavigationService.Activation.KEEP_CURRENT_WINDOW).get(10, TimeUnit.SECONDS);
        settle();
    }

    /** The caret in the selected tab's text. Swing thread only, as in {@link UiTestScope#await}. */
    private static int caret(MainWindow window) {
        return textArea(selected(window)).getCaretPosition();
    }

    /** The selected tab's text. Swing thread only. */
    private static String text(MainWindow window) {
        return textArea(selected(window)).getText();
    }

    private static Component selected(MainWindow window) {
        IEditorPanel editor = window.getEditorTabs().getSelectedEditor();
        assertNotNull(editor, "a tab is selected");
        return editor.getComponent();
    }

    /** The first editor text area in {@code component}. */
    private static EditorTextArea textArea(Component component) {
        EditorTextArea found = find(component);
        assertNotNull(found, "the tab shows text");
        return found;
    }

    private static EditorTextArea find(Component component) {
        if (component instanceof EditorTextArea area) return area;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                EditorTextArea found = find(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Lets the Swing steps queued by opening and placing run. */
    private static void settle() throws Exception {
        for (int step = 0; step < 3; step++) SwingUtilities.invokeAndWait(() -> { });
        Thread.sleep(200);
        SwingUtilities.invokeAndWait(() -> { });
    }
}
