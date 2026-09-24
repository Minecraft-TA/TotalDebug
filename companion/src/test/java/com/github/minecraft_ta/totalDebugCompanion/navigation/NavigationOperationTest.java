package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApplication;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.model.JavaEditorContext;
import com.github.minecraft_ta.totalDebugCompanion.model.ModView;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.FileTreeView;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.ScriptFileActions;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NavigationOperationTest {
    @TempDir Path directory;

    @Test void relocatedHistoryIgnoresOldCompletionWithoutClearingTheNewTraversal() throws Exception {
        try (var fixture = new Fixture()) {
            var oldReady = edt(fixture.first::holdNextReady);
            var oldBack = edt(fixture.navigation::goBack);
            oldReady.entered.get(5, TimeUnit.SECONDS);
            Path previous = fixture.first.getPath();
            Path moved = Files.move(previous, previous.resolveSibling("Moved.tdscript"));
            var newReady = edt(() -> {
                fixture.first.relocated(previous, moved);
                fixture.state.relocateFiles(previous, moved);
                fixture.tabs.setSelectedIndex(1);
                return fixture.first.holdNextReady();
            });
            var newBack = edt(fixture.navigation::goBack);
            newReady.entered.get(5, TimeUnit.SECONDS);
            var token = fixture.state.traversal.get();
            assertNotNull(token);
            oldReady.release.complete(null);
            assertCancelled(oldBack);
            edt(() -> {
                assertSame(token, fixture.state.traversal.get());
                assertEquals(new NavigationTarget.LocalFile(moved), fixture.back().target());
                assertTrue(fixture.first.offsets.isEmpty());
                assertTrue(fixture.first.restored.isEmpty());
                return null;
            });
            newReady.release.complete(null);
            newBack.get(5, TimeUnit.SECONDS);
            edt(() -> {
                assertNull(fixture.state.traversal.get());
                assertNull(fixture.back());
                assertEquals(new NavigationTarget.LocalFile(moved), fixture.state.currentEntry.target());
                assertEquals(List.of(0), fixture.first.offsets);
                assertEquals(List.of(fixture.savedState), fixture.first.restored);
                return null;
            });
        }
    }

    @Test void busyFileOperationRejectsHistoryAndDirectoryNavigationWithoutConsumingHistory() throws Exception {
        try (var fixture = new Fixture()) {
            var busy = ScriptFileActions.class.getDeclaredField("busy");
            busy.setAccessible(true);
            var destination = fixture.back();
            edt(() -> { busy.setBoolean(fixture.window.scriptFileActions(), true); return null; });
            try {
                assertCancelled(edt(fixture.navigation::goBack));
                assertCancelled(edt(() -> fixture.navigation.navigate(new NavigationTarget.LocalDirectory(fixture.first.getPath().getParent()),
                        NavigationService.Activation.KEEP_CURRENT_WINDOW)));
                edt(() -> {
                    assertEquals(destination, fixture.back());
                    assertNull(fixture.state.traversal.get());
                    assertSame(fixture.second, fixture.tabs.getSelectedEditor());
                    return null;
                });
            } finally { edt(() -> { busy.setBoolean(fixture.window.scriptFileActions(), false); return null; }); }
            edt(fixture.navigation::goBack).get(5, TimeUnit.SECONDS);
            assertNull(fixture.back());
        }
    }

    @Test void newNavigationSupersedesAHeldBackWithoutRestoringItsOldCaretOrHistory() throws Exception {
        try (var fixture = new Fixture()) {
            var ready = edt(fixture.first::holdNextReady);
            var back = edt(fixture.navigation::goBack);
            ready.entered.get(5, TimeUnit.SECONDS);
            edt(() -> fixture.navigation.navigate(new NavigationTarget.LocalFile(fixture.second.getPath(), 3),
                    NavigationService.Activation.KEEP_CURRENT_WINDOW)).get(5, TimeUnit.SECONDS);
            var destination = fixture.back();
            var current = fixture.state.currentEntry;
            ready.release.complete(null);
            assertCancelled(back);
            edt(() -> {
                assertEquals(destination, fixture.back());
                assertEquals(current, fixture.state.currentEntry);
                assertNull(fixture.state.traversal.get());
                assertTrue(fixture.first.offsets.isEmpty());
                assertTrue(fixture.first.restored.isEmpty());
                assertSame(fixture.second, fixture.tabs.getSelectedEditor());
                return null;
            });
        }
    }

    @Test void backSupersedesAnOrdinaryNavigationStillWaitingForItsEditor() throws Exception {
        try (var fixture = new Fixture()) {
            var ordinaryReady = edt(fixture.first::holdNextReady);
            var ordinary = edt(() -> fixture.navigation.navigate(new NavigationTarget.LocalFile(fixture.first.getPath(), 2),
                    NavigationService.Activation.KEEP_CURRENT_WINDOW));
            ordinaryReady.entered.get(5, TimeUnit.SECONDS);
            edt(fixture.navigation::goBack).get(5, TimeUnit.SECONDS);
            var current = fixture.state.currentEntry;
            ordinaryReady.release.complete(null);
            assertCancelled(ordinary);
            edt(() -> {
                assertEquals(current, fixture.state.currentEntry);
                assertNull(fixture.back());
                assertEquals(List.of(0), fixture.first.offsets);
                assertEquals(List.of(fixture.savedState), fixture.first.restored);
                return null;
            });
        }
    }

    @Test void localHistoryDoesNotActivateTheWindowAndExplicitActivationFailureCompletesTheRequest() throws Exception {
        var activations = new AtomicInteger();
        var refused = new IllegalStateException("Activation refused");
        try (var fixture = new Fixture(() -> { activations.incrementAndGet(); throw refused; })) {
            edt(fixture.navigation::goBack).get(5, TimeUnit.SECONDS);
            assertEquals(0, activations.get(), "Local history must not request OS foreground activation");
            var requested = edt(() -> fixture.navigation.navigate(new NavigationTarget.LocalFile(fixture.second.getPath()),
                    NavigationService.Activation.ACTIVATE_WINDOW));
            var failure = assertThrows(ExecutionException.class, () -> requested.get(5, TimeUnit.SECONDS));
            assertSame(refused, failure.getCause(), "Activation errors must complete the caller's future instead of stranding it");
            assertEquals(1, activations.get());
        }
    }

    @Test void modAndDefinitionPagesOpenOnceAndTakePartInHistory() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.navigation.navigate(new NavigationTarget.ModPage("examplemod"), NavigationService.Activation.KEEP_CURRENT_WINDOW).get(5, TimeUnit.SECONDS);
            fixture.navigation.navigate(new NavigationTarget.ModPage("examplemod", ModTab.RESOURCES, ""), NavigationService.Activation.KEEP_CURRENT_WINDOW).get(5, TimeUnit.SECONDS);
            assertEquals(1, edt(() -> fixture.tabs.editors().stream().filter(ModView.class::isInstance).count()));
            SubjectRef.Definition stone = new SubjectRef.Definition(SubjectRef.DefinitionKind.BLOCK, "minecraft:stone");
            fixture.navigation.navigate(new NavigationTarget.Definition(stone), NavigationService.Activation.KEEP_CURRENT_WINDOW).get(5, TimeUnit.SECONDS);
            assertEquals(new NavigationTarget.Definition(stone), edt(() -> fixture.tabs.getSelectedEditor().getNavigationTarget()));

            fixture.navigation.goBack().get(5, TimeUnit.SECONDS);

            assertInstanceOf(ModView.class, edt(fixture.tabs::getSelectedEditor));
        }
    }

    private final class Fixture implements AutoCloseable {
        final CompanionApplication app;
        final MainWindow window;
        final NavigationService navigation;
        final NavigationState state;
        final EditorTabs tabs;
        final FileTreeView isolatedTree;
        final HeldScriptView first;
        final HeldScriptView second;
        final NavigationViewState savedState = new NavigationViewState(9, 0, 20);

        Fixture() throws Exception { this(null); }
        Fixture(Runnable activation) throws Exception {
            Path home = Files.createDirectory(directory.resolve("home"));
            GlobalConfig.getInstance().loadFrom(home);
            app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token");
            app.openProject(CompanionProfile.forGame(Files.createDirectory(directory.resolve("game")))).get(10, TimeUnit.SECONDS);
            window = edt(app::createWindow);
            tabs = activation == null ? window.getEditorTabs() : edt(EditorTabs::new);
            isolatedTree = activation == null ? null : edt(() -> new FileTreeView(app::currentScope, ignored -> { }));
            navigation = activation == null ? window.navigation() : edt(() -> new NavigationService(window, tabs, isolatedTree,
                    app.currentScope(), window::editorContext, activation));
            state = app.currentScope().navigation();
            Path root = Files.createDirectories(app.currentScope().scriptFiles().root());
            first = new HeldScriptView(window.editorContext(), Files.writeString(root.resolve("First.tdscript"), "return 1;"));
            second = new HeldScriptView(window.editorContext(), Files.writeString(root.resolve("Second.tdscript"), "return 2;"));
            edt(() -> {
                tabs.openEditorTab(first);
                tabs.openEditorTab(second);
                state.history.recordNewNavigation(new NavigationEntry(new NavigationTarget.LocalFile(first.getPath()), null, savedState));
                return null;
            });
        }

        NavigationEntry back() { return state.history.destination(NavigationHistory.Direction.BACK, null); }

        @Override public void close() throws Exception {
            first.releaseAll();
            second.releaseAll();
            if (isolatedTree != null) {
                edt(() -> { tabs.closeMatching(view -> true); isolatedTree.dispose(); return null; });
                tabs.analysisExecutor().shutdownNow();
            }
            app.close();
        }
    }

    private static final class Ready {
        final CompletableFuture<Void> entered = new CompletableFuture<>();
        final CompletableFuture<Void> release = new CompletableFuture<>();
    }

    private static final class HeldScriptView extends ScriptView {
        private JPanel panel;
        final ArrayDeque<Ready> pending = new ArrayDeque<>();
        final List<Ready> gates = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>();
        final List<NavigationViewState> restored = new ArrayList<>();

        HeldScriptView(EditorContext context, Path path) { super(context, path); }
        Ready holdNextReady() { var gate = new Ready(); pending.add(gate); gates.add(gate); return gate; }
        void releaseAll() { gates.forEach(gate -> gate.release.complete(null)); }
        @Override public CompletableFuture<Void> ready() {
            var gate = pending.poll();
            if (gate == null) return CompletableFuture.completedFuture(null);
            gate.entered.complete(null);
            return gate.release;
        }
        @Override public Component getComponent() {
            if (panel == null) panel = new JPanel();
            return panel;
        }
        @Override public JavaEditorContext getJavaEditorContext() { return null; }
        @Override public void navigateToOffset(int offset) { offsets.add(offset); }
        @Override public NavigationViewState captureNavigationViewState() { return NavigationViewState.EMPTY; }
        @Override public void restoreNavigationViewState(NavigationViewState state) { restored.add(state); }
    }

    private static void assertCancelled(CompletableFuture<?> future) {
        Throwable failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        while (failure.getCause() != null) failure = failure.getCause();
        assertInstanceOf(CancellationException.class, failure);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
