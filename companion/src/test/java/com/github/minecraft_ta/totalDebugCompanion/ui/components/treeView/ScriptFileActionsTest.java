package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import javax.swing.JMenuItem;
import javax.swing.Action;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import java.util.Arrays;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyTreeNode;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApplication;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import org.junit.jupiter.api.BeforeEach;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine.BreakpointAction;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState.PersistedBreakpoint;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ScriptPanel;
import java.awt.event.ActionEvent;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService.Activation;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import javax.swing.JComponent;
import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.TransferHandler;
import javax.swing.DropMode;
import java.awt.datatransfer.Transferable;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import java.io.IOException;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import static org.junit.jupiter.api.Assertions.*;

class ScriptFileActionsTest {
    @ParameterizedTest @ValueSource(strings = {"create", "duplicate"})
    void recreatingAnExternallyDeletedOpenScriptPreservesItsDraft(String command) throws Exception {
        Path home = Files.createDirectories(directory.resolve("home"));
        GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            Path root = window.editorContext().project().scriptFiles().root();
            Path original = root.resolve("Original.tdscript");
            edt(() -> window.scriptFileActions().create(root, "Original", false, "return 1;")).get(10, TimeUnit.SECONDS);
            var view = edt(() -> (ScriptView) window.getEditorTabs().getSelectedEditor());
            edt(() -> {
                view.setFileOperation(true);
                find(view.getComponent(), RSyntaxTextArea.class).append("\n// unsaved draft");
                return null;
            });
            try {
                Files.delete(original);
                var recreate = edt(() -> command.equals("create")
                        ? window.scriptFileActions().create(root, "Original", false, "return 2;")
                        : window.scriptFileActions().duplicate(original, "Original"));
                var failure = assertThrows(ExecutionException.class, () -> recreate.get(10, TimeUnit.SECONDS));
                assertTrue(failure.getCause().getMessage().contains("Close it or choose another name"));
                assertFalse(Files.exists(original));
                assertSame(view, edt(() -> window.getEditorTabs().getSelectedEditor()));
                assertEquals("return 1;\n// unsaved draft", edt(view::currentText));
                edt(() -> window.scriptFileActions().duplicate(original, "Recovered")).get(10, TimeUnit.SECONDS);
                assertEquals("return 1;\n// unsaved draft", Files.readString(root.resolve("Recovered.tdscript")));
            } finally {
                edt(() -> { view.discardOnClose(); view.setFileOperation(false); window.dispose(); return null; });
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"script", "folder", "duplicate"})
    void creationOwnsItsRevealUntilTheEditorIsOpen(String kind) throws Exception {
        Path home = Files.createDirectories(directory.resolve("reveal-home"));
        GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("reveal-game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            var project = window.editorContext().project();
            Path root = Files.createDirectories(project.scriptFiles().root());
            Path source = Files.writeString(root.resolve("Original.tdscript"), "return 1;");
            var delegate = edt(() -> find(window, FileTreeView.class));
            var held = edt(() -> new HeldRevealTree(window, delegate));
            var treeField = ScriptFileActions.class.getDeclaredField("tree");
            treeField.setAccessible(true);
            edt(() -> { treeField.set(window.scriptFileActions(), held); return null; });
            try {
                var operation = edt(() -> kind.equals("duplicate") ? window.scriptFileActions().duplicate(source, "Created")
                        : window.scriptFileActions().create(root, "Created", kind.equals("folder"), "return 2;"));
                Path created = held.arrived.get(10, TimeUnit.SECONDS);
                assertTrue(Files.exists(created));
                edt(() -> {
                    assertTrue(window.scriptFileActions().isBusy());
                    assertFalse(window.canExit());
                    assertFalse(window.prepareProjectSwitch());
                    assertTrue(window.scriptFileActions().create(root, "TooEarly", true, "").isCompletedExceptionally());
                    return null;
                });
                var other = CompanionProfile.forGame(Files.createDirectories(directory.resolve("other-game")));
                assertThrows(ExecutionException.class, () -> app.openProject(other).get(10, TimeUnit.SECONDS));
                assertSame(project, app.currentScope());
                assertFalse(operation.isDone());
                // A switch attempt marks the scope before the EDT gets to veto it.
                project.beginSwitch();
                try {
                    held.release.complete(null);
                    operation.get(10, TimeUnit.SECONDS);
                } finally { project.cancelSwitch(); }
                edt(() -> {
                    assertFalse(window.scriptFileActions().isBusy());
                    if (!kind.equals("folder")) assertEquals(created, ((ScriptView) window.getEditorTabs().getSelectedEditor()).getPath());
                    return null;
                });
            } finally {
                held.release.complete(null);
                edt(() -> { treeField.set(window.scriptFileActions(), delegate); held.dispose(); return null; });
            }
        }
    }

    @Test void revealFailureIsReportedAndReleasesTheFileOperation() throws Exception {
        Path home = Files.createDirectories(directory.resolve("failed-reveal-home"));
        GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("failed-reveal-game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            Path root = window.editorContext().project().scriptFiles().root();
            var delegate = edt(() -> find(window, FileTreeView.class));
            var held = edt(() -> new HeldRevealTree(window, delegate));
            var treeField = ScriptFileActions.class.getDeclaredField("tree");
            treeField.setAccessible(true);
            edt(() -> { treeField.set(window.scriptFileActions(), held); return null; });
            try {
                var operation = edt(() -> window.scriptFileActions().create(root, "Created", true, ""));
                Path created = held.arrived.get(10, TimeUnit.SECONDS);
                held.release.completeExceptionally(new IOException("Reveal failed"));
                assertThrows(ExecutionException.class, () -> operation.get(10, TimeUnit.SECONDS));
                assertTrue(Files.isDirectory(created), "UI failure must not pretend the completed disk mutation was rolled back");
                assertFalse(edt(() -> window.scriptFileActions().isBusy()));
            } finally {
                held.release.complete(null);
                edt(() -> { treeField.set(window.scriptFileActions(), delegate); held.dispose(); return null; });
            }
            edt(() -> window.scriptFileActions().create(root, "Next", true, "")).get(10, TimeUnit.SECONDS);
        }
    }

    private static final class HeldRevealTree extends FileTreeView {
        private final FileTreeView delegate;
        final CompletableFuture<Path> arrived = new CompletableFuture<>();
        final CompletableFuture<Void> release = new CompletableFuture<>();
        HeldRevealTree(MainWindow window, FileTreeView delegate) {
            super(() -> window.editorContext().project(), ignored -> { });
            this.delegate = delegate;
        }
        @Override public CompletableFuture<Void> refreshDirectory(Path parent) { return delegate.refreshDirectory(parent); }
        @Override public CompletableFuture<Boolean> revealLocalPath(Path path) {
            return delegate.revealLocalPath(path).thenCompose(found -> {
                arrived.complete(path);
                return release.thenApply(ignored -> found);
            });
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void loadingReplacementDistinguishesSwitchPreparationFromRetirement(boolean retired) throws Exception {
        Path home = Files.createDirectories(directory.resolve("load-home"));
        GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("load-game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            var project = window.editorContext().project();
            Path root = Files.createDirectories(project.scriptFiles().root());
            Path original = Files.writeString(root.resolve("Preview.txt"), "return 42;");
            edt(() -> window.navigation().navigate(new NavigationTarget.LocalFile(original), Activation.KEEP_CURRENT_WINDOW)).get(10, TimeUnit.SECONDS);
            var previous = edt(() -> window.getEditorTabs().getSelectedEditor());
            Path script = Files.move(original, root.resolve("Converted.tdscript"));
            var loaded = edt(() -> {
                var pending = window.navigation().relocatePreview(previous, script);
                // The loader cannot install until this EDT turn finishes.
                project.beginSwitch();
                if (retired) project.retire();
                return pending;
            });
            if (retired) {
                assertThrows(ExecutionException.class, () -> loaded.get(10, TimeUnit.SECONDS));
                assertSame(previous, edt(() -> window.getEditorTabs().getSelectedEditor()));
            } else {
                try {
                    loaded.get(10, TimeUnit.SECONDS);
                    var replacement = edt(() -> (ScriptView) window.getEditorTabs().getSelectedEditor());
                    assertEquals(script, replacement.getPath());
                } finally { project.cancelSwitch(); }
            }
        }
    }

    @Test void convertingAPreviewToAScriptReadsItsContentsOffTheEdt() throws Exception {
        Path home = Files.createDirectories(directory.resolve("preview-home"));
        GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("preview-game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            Path root = Files.createDirectories(window.editorContext().project().scriptFiles().root());
            Path original = Files.writeString(root.resolve("Preview.txt"), "return 42;");
            edt(() -> window.navigation().navigate(new NavigationTarget.LocalFile(original), Activation.KEEP_CURRENT_WINDOW)).get(10, TimeUnit.SECONDS);
            Path script = root.resolve("Converted.tdscript");
            Path capture = directory.resolve("script-load.jfr");
            try (var recording = new Recording()) {
                recording.enable("jdk.FileRead").withThreshold(Duration.ZERO);
                recording.start();
                edt(() -> window.scriptFileActions().rename(original, script)).get(10, TimeUnit.SECONDS);
                recording.stop();
                recording.dump(capture);
            }
            var reads = RecordingFile.readAllEvents(capture).stream()
                    .filter(event -> event.getEventType().getName().equals("jdk.FileRead"))
                    .filter(event -> script.toString().equals(event.getString("path"))).toList();
            assertFalse(reads.isEmpty(), "The probe must observe the actual script content read");
            assertTrue(reads.stream().noneMatch(event -> event.getThread().getJavaName().startsWith("AWT-EventQueue")),
                    "Replacing a preview must not read script contents on the EDT");
            var replacement = edt(() -> (ScriptView) window.getEditorTabs().getSelectedEditor());
            assertEquals("return 42;", replacement.getSourceText());
            assertEquals(script, replacement.getPath());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void closeChecksRecheckOperationsStartedDuringSaveEvents(boolean switching) throws Exception {
        Path home = Files.createDirectories(directory.resolve("reentrant-home"));
        GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("reentrant-game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            var project = window.editorContext().project();
            Path root = project.scriptFiles().root();
            var operation = new AtomicReference<CompletableFuture<Void>>();
            IEditorPanel saving = new IEditorPanel() {
                private final JPanel panel = new JPanel();
                @Override public String getTitle() { return "Saving"; }
                @Override public String getTooltip() { return "Saving"; }
                @Override public Icon getIcon() { return null; }
                @Override public Component getComponent() { return panel; }
                @Override public boolean canClose() {
                    if (operation.get() == null) {
                        var loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
                        SwingUtilities.invokeLater(() -> {
                            operation.set(window.scriptFileActions().create(root, "DuringSave", true, ""));
                            loop.exit();
                        });
                        assertTrue(loop.enter());
                    }
                    return true;
                }
            };
            edt(() -> window.getEditorTabs().openEditorTab(saving)).get(10, TimeUnit.SECONDS);
            edt(() -> {
                assertFalse(switching ? window.prepareProjectSwitch() : window.canExit());
                assertTrue(window.isEnabled());
                assertTrue(project.isActive());
                return null;
            });
            operation.get().get(10, TimeUnit.SECONDS);
            assertTrue(Files.isDirectory(root.resolve("DuringSave")));
            assertTrue(edt(() -> switching ? window.prepareProjectSwitch() : window.canExit()));
        }
    }

    @Test void busyCreationCommandsReportWhyTheyCannotRun() throws Exception {
        Path home = Files.createDirectories(directory.resolve("busy-home"));
        GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("busy-game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            var actions = window.scriptFileActions();
            var operation = edt(() -> {
                var work = actions.create(window.editorContext().project().scriptFiles().root(), "First", true, "");
                assertTrue(actions.isBusy());
                actions.newScript(); actions.newFolder(); actions.saveAsScript("return 1;");
                return work;
            });
            operation.get(10, TimeUnit.SECONDS);
            edt(() -> {
                var entries = app.notifications().snapshot().entries();
                assertEquals(3, entries.size());
                assertTrue(entries.stream().allMatch(entry -> entry.details().contains("Another file operation is in progress.")));
                return null;
            });
        }
    }

    @Test void contextMenuAndCreationUseLoadedFolderMetadata() throws Exception {
        Path home = Files.createDirectories(directory.resolve("metadata-home"));
        GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("metadata-game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            Path folder = window.editorContext().project().scriptFiles().create(
                    window.editorContext().project().scriptFiles().root(), "Folder.tdscript", true, "");
            FileTreeView files = find(window, FileTreeView.class);
            var item = files.tree().getItemFactory().createFileSystemDirectoryItem(folder, false);
            edt(() -> { files.tree().setRootNodes(item); files.tree().setSelectionRow(0); return null; });
            // A loaded row still describes its folder when the backing filesystem is unavailable.
            Files.delete(folder);
            edt(() -> {
                JPopupMenu menu = files.createContextMenu(item);
                assertTrue(labels(menu).contains("New Folder"));
                assertTrue(labels(menu).contains("Delete folder"));
                assertFalse(labels(menu).contains("Duplicate script"));
                var parent = ScriptFileActions.class.getDeclaredMethod("creationParent");
                parent.setAccessible(true);
                assertEquals(folder, parent.invoke(window.scriptFileActions()));
                return null;
            });
        }
    }

    @Test void compactMiddleFolderActionsKeepTheirRealTargetAndRelocateOpenScripts() throws Exception {
        Path home = Files.createDirectories(directory.resolve("home"));
        GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            Path root = window.editorContext().project().scriptFiles().root();
            Path items = Files.createDirectories(root.resolve("modules/client/items"));
            Path script = Files.writeString(items.resolve("Test.tdscript"), "return 1;");
            FileTreeView files = find(window, FileTreeView.class);
            edt(() -> { files.reloadProfile(); window.getEditorTabs().openEditorTab(new ScriptView(window.editorContext(), script)); return null; });
            ScriptView editor = edt(() -> (ScriptView) window.getEditorTabs().getSelectedEditor());
            assertTrue(files.revealLocalPath(root.resolve("modules/client")).get(5, TimeUnit.SECONDS));
            Path target = edt(() -> ScriptFileActions.path(files.tree().getSelectionPath()));
            assertEquals(root.resolve("modules/client"), target);
            edt(() -> {
                var node = (LazyTreeNode) files.tree().getSelectionPath().getLastPathComponent();
                var menu = files.createContextMenu(node.selectedItem());
                var copy = Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast)
                        .filter(item -> item.getText().equals("Copy path")).findFirst().orElseThrow();
                assertEquals(target.toString(), copy.getAction().getValue(Action.ACTION_COMMAND_KEY));
                return null;
            });
            edt(() -> window.scriptFileActions().rename(target, root.resolve("modules/server"))).get(10, TimeUnit.SECONDS);
            assertEquals(root.resolve("modules/server/items/Test.tdscript"), editor.getPath());
            assertTrue(Files.isRegularFile(editor.getPath()));
            assertFalse(Files.exists(target));
            Path destination = Files.createDirectories(root.resolve("Destination"));
            assertTrue(files.revealLocalPath(root.resolve("modules/server")).get(5, TimeUnit.SECONDS));
            Path moved = edt(() -> ScriptFileActions.path(files.tree().getSelectionPath()));
            edt(() -> window.scriptFileActions().move(List.of(moved), destination)).get(10, TimeUnit.SECONDS);
            assertEquals(destination.resolve("server/items/Test.tdscript"), editor.getPath());
            assertTrue(Files.isRegularFile(editor.getPath()));
            edt(() -> { window.dispose(); return null; });
        }
    }

    @TempDir Path directory;
    @BeforeEach void configureEditor() throws Exception {
        var configure = CompanionApp.class.getDeclaredMethod("configureLookAndFeel");
        configure.setAccessible(true);
        configure.invoke(null);
    }
    private static List<String> labels(JPopupMenu menu) {
        return Arrays.stream(menu.getComponents()).map(component -> ((JMenuItem) component).getText()).toList();
    }
    @Test void failedSavesCompleteWithoutAModalAndDoNotResurrectDismissedNotifications() throws Exception {
        Path home = Files.createDirectories(directory.resolve("save-home"));
        GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "save-test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("save-game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            var files = window.editorContext().project().scriptFiles();
            Path script = files.create(files.root(), "SaveTest", false, "return 1;");
            var originalTime = Files.getLastModifiedTime(script);
            edt(() -> window.navigation().navigate(new NavigationTarget.LocalFile(script), Activation.KEEP_CURRENT_WINDOW)).get(10, TimeUnit.SECONDS);
            ScriptView view = edt(() -> (ScriptView) window.getEditorTabs().getSelectedEditor());
            RSyntaxTextArea editor = edt(() -> find(view.getComponent(), RSyntaxTextArea.class));
            Files.writeString(script, "// externally changed");
            CompletableFuture<Void> failed = edt(() -> {
                editor.setText("return 2;");
                editor.getActionMap().get("saveScript").actionPerformed(new ActionEvent(editor, 0, "save"));
                return view.pendingSave();
            });
            assertThrows(ExecutionException.class, () -> failed.get(10, TimeUnit.SECONDS));
            assertEquals(1, app.notifications().snapshot().entries().size());
            long id = app.notifications().snapshot().entries().getFirst().id();
            app.notifications().dismiss(id);
            CompletableFuture<Void> retry = edt(() -> {
                editor.getActionMap().get("saveScript").actionPerformed(new ActionEvent(editor, 0, "save"));
                return view.pendingSave();
            });
            assertThrows(ExecutionException.class, () -> retry.get(10, TimeUnit.SECONDS));
            assertTrue(app.notifications().snapshot().entries().isEmpty());
            Path renamed = script.resolveSibling("Renamed.tdscript");
            var rename = edt(() -> window.scriptFileActions().rename(script, renamed));
            assertThrows(ExecutionException.class, () -> rename.get(10, TimeUnit.SECONDS));
            assertFalse(Files.exists(renamed));
            Files.writeString(script, "return 1;");
            Files.setLastModifiedTime(script, originalTime);
            edt(() -> {
                editor.getActionMap().get("saveScript").actionPerformed(new ActionEvent(editor, 0, "save"));
                return view.pendingSave();
            }).get(10, TimeUnit.SECONDS);
            assertEquals("return 2;", Files.readString(script));
            assertTrue(app.notifications().snapshot().entries().isEmpty());
            edt(() -> { window.dispose(); return null; });
        }
    }
    @Test void movingOpenFolderPreservesDraftUndoAndEditorAndDeletesWithoutResurrection() throws Exception {
        Path home = Files.createDirectories(directory.resolve("home"));
        GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            var files = window.editorContext().project().scriptFiles();
            Path folder = files.create(files.root(), "Folder", true, "");
            Path destination = files.create(files.root(), "Destination", true, "");
            Path script = files.create(folder, "Test", false, "return 1;");
            edt(() -> { window.refreshRuntimeSources(); return null; });
            edt(() -> window.editorContext().navigation().navigate(new NavigationTarget.LocalFile(script), Activation.KEEP_CURRENT_WINDOW)).get(10, TimeUnit.SECONDS);
            ScriptView view = edt(() -> (ScriptView) window.getEditorTabs().getSelectedEditor());
            RSyntaxTextArea editor = edt(() -> find(view.getComponent(), RSyntaxTextArea.class));
            String key = edt(() -> view.getJavaEditorContext().astKey());
            edt(() -> {
                editor.append("\n// draft");
                editor.getActionMap().get("saveScript").actionPerformed(new ActionEvent(editor, 0, "save"));
                return null;
            });
            edt(() -> window.scriptFileActions().move(List.of(folder), destination)).get(10, TimeUnit.SECONDS);
            Path moved = destination.resolve("Folder/Test.tdscript");
            assertEquals(moved, edt(view::getPath));
            assertEquals("return 1;\n// draft", Files.readString(moved));
            assertFalse(Files.exists(folder));
            assertSame(view, edt(() -> window.getEditorTabs().getSelectedEditor()));
            assertEquals(key, edt(() -> view.getJavaEditorContext().astKey()));
            edt(() -> { editor.undoLastAction(); return null; });
            assertEquals("return 1;", edt(editor::getText));
            edt(() -> window.scriptFileActions().rename(moved, moved.resolveSibling("Renamed.tdscript"))).get(10, TimeUnit.SECONDS);
            assertEquals("Renamed.tdscript", edt(view::getTitle));
            assertSame(editor, edt(() -> find(view.getComponent(), RSyntaxTextArea.class)));
            edt(() -> window.scriptFileActions().duplicate(view.getPath(), "Copy")).get(10, TimeUnit.SECONDS);
            assertEquals("return 1;", Files.readString(view.getPath().resolveSibling("Copy.tdscript")));
            edt(() -> window.scriptFileActions().delete(List.of(destination), false)).get(10, TimeUnit.SECONDS);
            assertFalse(Files.exists(destination));
            assertEquals(0, edt(() -> window.getEditorTabs().getTabCount()));
            edt(() -> { window.dispose(); return null; });
        }
    }
    @Test void sameNamesInDifferentFoldersAndNewFolderUseTheRealActionPath() throws Exception {
        Path home = Files.createDirectories(directory.resolve("home")); GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            Path root = window.editorContext().project().scriptFiles().root();
            edt(() -> window.scriptFileActions().create(root, "One", true, "")).get(10, TimeUnit.SECONDS);
            edt(() -> window.scriptFileActions().create(root, "Two", true, "")).get(10, TimeUnit.SECONDS);
            FileTreeView treeView = edt(() -> find(window, FileTreeView.class));
            edt(() -> window.editorContext().navigation().navigate(new NavigationTarget.LocalDirectory(root), Activation.KEEP_CURRENT_WINDOW)).get(10, TimeUnit.SECONDS);
            assertEquals(root, edt(() -> ScriptFileActions.path(treeView.tree().getSelectionPath())));
            edt(() -> { treeView.tree().collapseRow(0); return null; });
            for (String folder : List.of("One", "Two")) edt(() -> window.scriptFileActions().create(root.resolve(folder), "Test", false, "return 1;")).get(10, TimeUnit.SECONDS);
            assertEquals(2, edt(() -> window.getEditorTabs().getTabCount()));
            edt(() -> {
                var selected = treeView.tree().getSelectionPath();
                assertEquals(root.resolve("Two/Test.tdscript"), ScriptFileActions.path(selected));
                assertTrue(treeView.tree().isExpanded(selected.getParentPath()));
                assertTrue(treeView.tree().isVisible(selected));
                return null;
            });
            treeView.revealLocalPath(root.resolve("One/Test.tdscript")).get(10, TimeUnit.SECONDS);
            edt(() -> {
                var tree = treeView.tree();
                assertTrue(tree.getDragEnabled()); assertEquals(DropMode.ON, tree.getDropMode());
                assertEquals(TransferHandler.MOVE, tree.getTransferHandler().getSourceActions(tree));
                var create = tree.getTransferHandler().getClass().getDeclaredMethod("createTransferable", JComponent.class); create.setAccessible(true);
                var transfer = (Transferable) create.invoke(tree.getTransferHandler(), tree);
                assertTrue(transfer.getTransferDataFlavors()[0].isMimeTypeEqual("application/x-java-jvm-local-objectref"));
                assertNotNull(transfer.getTransferData(transfer.getTransferDataFlavors()[0]));
                var menu = treeView.createContextMenu(((LazyTreeNode) tree.getSelectionPath().getLastPathComponent()).getUserObject());
                assertEquals(List.of("Rename", "Move to...", "Duplicate script", "Copy path", "Delete file"), labels(menu));
                assertEquals(KeyStroke.getKeyStroke("F2"), ((JMenuItem) menu.getComponent(0)).getAccelerator());
                var folderMenu = treeView.createContextMenu(tree.getItemFactory().createFileSystemDirectoryItem(root.resolve("One"), false));
                assertEquals(List.of("New Script", "New Folder", "Rename", "Move to...", "Copy path", "Delete folder"), labels(folderMenu));
                var rootMenu = treeView.createContextMenu(tree.getItemFactory().createFileSystemDirectoryItem(root, false));
                assertEquals(List.of("New Script", "New Folder", "Copy path"), labels(rootMenu));
                return null;
            });
            var firstSelection = edt(() -> treeView.tree().getSelectionPath());
            treeView.revealLocalPath(root.resolve("Two/Test.tdscript")).get(10, TimeUnit.SECONDS);
            edt(() -> {
                treeView.tree().addSelectionPath(firstSelection);
                var menu = treeView.createContextMenu(treeView.tree().getItemFactory().createFileSystemFileItem(root.resolve("One/Test.tdscript")));
                assertEquals(List.of("Move to...", "Copy path", "Delete"), labels(menu));
                return null;
            });
            assertNotEquals(edt(() -> window.getEditorTabs().editors().get(0).getTooltip()), edt(() -> window.getEditorTabs().editors().get(1).getTooltip()));
            assertThrows(Exception.class, () -> edt(() -> window.scriptFileActions().move(List.of(root.resolve("One/Test.tdscript")), root.resolve("Two"))).get(10, TimeUnit.SECONDS));
            assertTrue(Files.exists(root.resolve("One/Test.tdscript")));
            edt(() -> window.scriptFileActions().create(root, "Empty", true, "")).get(10, TimeUnit.SECONDS);
            assertThrows(Exception.class, () -> edt(() -> window.scriptFileActions().move(List.of(root.resolve("One/Test.tdscript"), root.resolve("Two/Test.tdscript")), root.resolve("Empty"))).get(10, TimeUnit.SECONDS));
            assertTrue(Files.exists(root.resolve("One/Test.tdscript")));
            assertTrue(Files.exists(root.resolve("Two/Test.tdscript")));
            assertFalse(Files.exists(root.resolve("Empty/Test.tdscript")));
            edt(() -> { window.dispose(); return null; });
        }
    }
    @Test void referencesFollowMovesAndRunningScriptsCannotBeMovedOrDeleted() throws Exception {
        Path home = Files.createDirectories(directory.resolve("home")); GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            var scope = window.editorContext().project();
            Path root = scope.scriptFiles().root();
            edt(() -> window.scriptFileActions().create(root, "Original", false, "return 1;")).get(10, TimeUnit.SECONDS);
            ScriptView view = edt(() -> (ScriptView) window.getEditorTabs().getSelectedEditor());
            var persisted = new PersistedBreakpoint("file:/Example.java", "Example", 1, 1, null, null, null, null, null, true,
                    new BreakpointAction(null, "Original.tdscript", true));
            scope.state().setDebuggerBreakpoints("one", List.of(persisted));
            scope.state().setDebuggerBreakpoints("two", List.of(persisted));
            var state = ScriptPanel.class.getDeclaredMethod("setRunButtonsState", boolean.class); state.setAccessible(true);
            edt(() -> { state.invoke(view.getComponent(), false); return null; });
            assertThrows(ExecutionException.class, () -> edt(() -> window.scriptFileActions().rename(view.getPath(), root.resolve("Renamed.tdscript"))).get(10, TimeUnit.SECONDS));
            Path destination = Files.createDirectory(root.resolve("destination"));
            assertThrows(ExecutionException.class, () -> edt(() -> window.scriptFileActions().move(List.of(view.getPath()), destination)).get(10, TimeUnit.SECONDS));
            assertTrue(Files.exists(root.resolve("Original.tdscript")));
            edt(() -> { state.invoke(view.getComponent(), true); return null; });
            edt(() -> window.scriptFileActions().rename(root.resolve("Original.tdscript"), root.resolve("Renamed.tdscript"))).get(10, TimeUnit.SECONDS);
            edt(() -> { state.invoke(view.getComponent(), false); return null; });
            assertTrue(edt(view::isRunning));
            assertEquals("Renamed.tdscript", scope.state().debuggerBreakpoints("one").getFirst().action().script());
            assertEquals("Renamed.tdscript", scope.state().debuggerBreakpoints("two").getFirst().action().script());
            assertThrows(Exception.class, () -> edt(() -> window.scriptFileActions().delete(List.of(view.getPath()), false)).get(10, TimeUnit.SECONDS));
            edt(() -> { state.invoke(view.getComponent(), true); return null; });
            assertThrows(Exception.class, () -> edt(() -> window.scriptFileActions().delete(List.of(view.getPath()), false)).get(10, TimeUnit.SECONDS));
            assertTrue(Files.exists(view.getPath()));
            scope.state().setDebuggerBreakpoints("one", List.of()); scope.state().setDebuggerBreakpoints("two", List.of());
            edt(() -> window.scriptFileActions().delete(List.of(view.getPath()), false)).get(10, TimeUnit.SECONDS);
            assertFalse(Files.exists(view.getPath()));
            edt(() -> { window.dispose(); return null; });
        }
    }

    @Test void duplicateCanRecoverDraftAfterExternalReplacementAndPreviewMovesWithFolder() throws Exception {
        Path home = Files.createDirectories(directory.resolve("home")); GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            var files = window.editorContext().project().scriptFiles();
            Path root = files.root();
            edt(() -> window.scriptFileActions().create(root, "Folder", true, "")).get(10, TimeUnit.SECONDS);
            edt(() -> window.scriptFileActions().create(root.resolve("Folder"), "Test", false, "original")).get(10, TimeUnit.SECONDS);
            ScriptView view = edt(() -> (ScriptView) window.getEditorTabs().getSelectedEditor());
            edt(() -> { find(view.getComponent(), RSyntaxTextArea.class).append(" draft"); return null; });
            Files.writeString(view.getPath(), "external replacement");
            edt(() -> window.scriptFileActions().duplicate(view.getPath(), "Recovered")).get(10, TimeUnit.SECONDS);
            assertEquals("original draft", Files.readString(root.resolve("Folder/Recovered.tdscript")));
            assertEquals("external replacement", Files.readString(view.getPath()));
            edt(() -> { view.discardOnClose(); window.getEditorTabs().closeMatching(candidate -> candidate == view); return null; });
            Path preview = Files.writeString(root.resolve("Folder/note.txt"), "note");
            edt(() -> window.editorContext().navigation().navigate(new NavigationTarget.LocalFile(preview), Activation.KEEP_CURRENT_WINDOW)).get(10, TimeUnit.SECONDS);
            edt(() -> window.scriptFileActions().rename(root.resolve("Folder"), root.resolve("Moved"))).get(10, TimeUnit.SECONDS);
            assertEquals(new NavigationTarget.LocalFile(root.resolve("Moved/note.txt")), edt(() -> window.getEditorTabs().getSelectedEditor().getNavigationTarget()));
            edt(() -> { window.dispose(); return null; });
        }
    }

    @Test void closeAfterUndoWaitsForTheSaveResultToReachTheEditor() throws Exception {
        Path home = Files.createDirectories(directory.resolve("home")); GlobalConfig.getInstance().loadFrom(home);
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(10, TimeUnit.SECONDS);
            MainWindow window = edt(app::createWindow);
            Path root = window.editorContext().project().scriptFiles().root();
            edt(() -> window.scriptFileActions().create(root, "Test", false, "A")).get(10, TimeUnit.SECONDS);
            ScriptView view = edt(() -> (ScriptView) window.getEditorTabs().getSelectedEditor());
            edt(() -> {
                var editor = find(view.getComponent(), RSyntaxTextArea.class);
                editor.setText("B");
                editor.getActionMap().get("saveScript").actionPerformed(new ActionEvent(editor, 0, "save"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                // Hold publication on the EDT until the background atomic write has completed.
                while (!Files.readString(view.getPath()).equals("B") && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals("B", Files.readString(view.getPath()));
                assertFalse(view.pendingSave().isDone());
                editor.setText("A");
                assertTrue(view.canClose());
                assertEquals("A", Files.readString(view.getPath()));
                window.dispose();
                return null;
            });
        }
    }

    private static <T extends Component> T find(Component root, Class<T> type) {
        if(type.isInstance(root)) return type.cast(root);
        if(root instanceof Container container) for(var child: container.getComponents()) { T found=find(child,type); if(found!=null)return found; }
        return null;
    }
    private static <T> T edt(Callable<T> call) throws Exception { var task=new FutureTask<>(call); SwingUtilities.invokeAndWait(task); return task.get(); }
}
