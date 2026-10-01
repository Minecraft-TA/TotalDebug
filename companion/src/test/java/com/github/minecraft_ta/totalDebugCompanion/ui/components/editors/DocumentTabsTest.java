package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApplication;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
