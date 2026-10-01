package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApplication;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.catalog.LevelDatFixture;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.WorldTab;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyTreeNode;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project tree as the window shows it (docs/PROJECT_TREE.md, finish line): its roots as the project's state gives
 * them, and Show in Project reaching every kind of row.
 */
@UiTest
class ProjectTreeTest {
    @TempDir Path directory;

    @BeforeAll
    static void registerTokenMakers() {
        CompanionApp.configureTokenMakers();
    }

    /** Prepares the game directory before the project opens. */
    @FunctionalInterface
    private interface Before {
        void prepare(Path game) throws Exception;
    }

    /** What a test does with the window of a project opened on {@code game}. */
    @FunctionalInterface
    private interface WithWindow {
        void run(CompanionApplication app, MainWindow window, FileTreeView tree, Path game) throws Exception;
    }

    private void withWindow(Before before, WithWindow body) throws Exception {
        Path home = Files.createDirectory(this.directory.resolve("home"));
        GlobalConfig.getInstance().loadFrom(home);
        Path game = Files.createDirectory(this.directory.resolve("game"));
        before.prepare(game);
        try (CompanionApplication app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(game)).get(10, TimeUnit.SECONDS);
            MainWindow window = UiTestScope.onEdt(app::createWindow);
            UiTestScope.onEdt(() -> {
                window.setSize(1280, 720);
                UiTestScope.show(window);
            });
            FileTreeView tree = UiTestScope.onEdt(() -> find(window, FileTreeView.class));
            assertNotNull(tree, "the window shows the Project tree");
            settle();
            body.run(app, window, tree, game);
        }
    }

    @Test
    void showInProjectReachesEveryRowOfTheModpack() throws Exception {
        withWindow(game -> Files.writeString(Files.createDirectories(game.resolve("logs")).resolve("latest.log"), "started\n"),
                (app, window, tree, game) -> {
            ProjectScope scope = app.currentScope();
            CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)).write(scope.paths().catalog());
            scope.catalog().accept(CatalogFixtures.INVENTORY, scope.paths().catalog(), Runnable::run);
            scope.changes().changed(new ChangeRecord.Setting("testmod", "testmod-common.toml", game.resolve("config/testmod-common.toml"), "widgets.speed"), "4", "8");
            settle();

            assertReveals(tree, tree::revealPackConfiguration, ModTreeItems.CONFIGURATION);
            assertReveals(tree, tree::revealPackResources, ModTreeItems.RESOURCES);
            assertReveals(tree, tree::revealKeyBindings, ModTreeItems.KEY_BINDINGS);
            assertReveals(tree, tree::revealLogs, ModTreeItems.LOGS);
            assertReveals(tree, tree::revealChanges, ModTreeItems.CHANGES);
            assertReveals(tree, () -> tree.revealContent(""), ModTreeItems.CONTENT);
            assertReveals(tree, () -> tree.revealModPage(new NavigationTarget.ModPage("testmod")), "testmod");
            assertReveals(tree, () -> tree.revealModPage(new NavigationTarget.ModPage("testmod", ModTab.CONFIGURATION, "")),
                    ModTreeItems.groupName(ModTab.CONFIGURATION));
        });
    }

    @Test
    void showInProjectReachesTheWorldAndItsTabs() throws Exception {
        withWindow(game -> LevelDatFixture.write(game.resolve("saves/World"), LevelDatFixture.world("World")),
                (app, window, tree, game) -> {
            app.currentScope().location().connected(message -> true);
            app.currentScope().location().playing(new PlayingPayload.Singleplayer(game.resolve("saves/World").toString()));
            UiTestScope.await(() -> app.currentScope().world().published().map(read -> read.saved() != null).orElse(false));
            settle();

            assertReveals(tree, () -> tree.revealWorld(WorldTab.OVERVIEW), WorldTreeItems.ROOT);
            assertReveals(tree, () -> tree.revealWorld(WorldTab.GAME_RULES), WorldTreeItems.rowName(WorldTab.GAME_RULES));
            assertReveals(tree, () -> tree.revealWorld(WorldTab.DATAPACKS), WorldTreeItems.rowName(WorldTab.DATAPACKS));
        });
    }

    @Test
    void theFirstChangeRecordedShowsTheChangesRow() throws Exception {
        withWindow(game -> { }, (app, window, tree, game) -> {
            app.currentScope().changes().changed(new ChangeRecord.Setting("testmod", "testmod-common.toml", game.resolve("config/testmod-common.toml"), "widgets.speed"), "4", "8");
            assertReveals(tree, tree::revealChanges, ModTreeItems.CHANGES);
        });
    }

    @Test
    void theGamePlayingAWorldAddsTheWorldRoot() throws Exception {
        withWindow(game -> { }, (app, window, tree, game) -> {
            assertFalse(UiTestScope.onEdt(() -> tree.tree().hasRootNode(WorldTreeItems.ROOT)), "no world, no World root");
            // On a server, the world is the server's, and the instance has no saves of its own.
            app.currentScope().location().connected(message -> true);
            app.currentScope().location().playing(new PlayingPayload.Multiplayer("play.example.invalid", false, true));
            UiTestScope.await(() -> tree.tree().hasRootNode(WorldTreeItems.ROOT));

            // The game makes the instance's first singleplayer world and plays it.
            Path world = LevelDatFixture.write(game.resolve("saves/First"), LevelDatFixture.world("First")).getParent();
            app.currentScope().location().playing(new PlayingPayload.Singleplayer(world.toString()));
            UiTestScope.await(() -> tree.tree().hasRootNode(WorldTreeItems.ROOT) && app.currentScope().world().published()
                    .map(read -> world.equals(read.directory())).orElse(false));
            settle();
            assertReveals(tree, () -> tree.revealWorld(WorldTab.GAME_RULES), WorldTreeItems.rowName(WorldTab.GAME_RULES));
        });
    }

    @Test
    void showInProjectReachesAScriptFolderAndAScript() throws Exception {
        withWindow(game -> { }, (app, window, tree, game) -> {
            Path folder = Files.createDirectories(app.currentScope().scriptFiles().root().resolve("tools/nested"));
            Path script = Files.writeString(folder.resolve("Gear.tdscript"), "return 1;");
            assertTrue(tree.revealLocalPath(folder).get(10, TimeUnit.SECONDS), "the folder is revealed");
            assertTrue(tree.revealLocalPath(script.getParent()).get(10, TimeUnit.SECONDS));
            assertEquals("nested", UiTestScope.onEdt(() -> selectedName(tree)));
        });
    }

    /** Runs a reveal of {@code tree} and checks it selected the row named {@code name}. */
    private static void assertReveals(FileTreeView tree, Callable<CompletableFuture<Boolean>> reveal, String name) throws Exception {
        CompletableFuture<Boolean> revealed = UiTestScope.onEdt(reveal);
        assertTrue(revealed.get(10, TimeUnit.SECONDS), name + " is revealed");
        settle();
        assertEquals(name, UiTestScope.onEdt(() -> selectedName(tree)), name + " is selected");
    }

    private static String selectedName(FileTreeView tree) {
        var path = tree.tree().getSelectionPath();
        if (path == null || !(path.getLastPathComponent() instanceof LazyTreeNode node)) return "";
        return node.selectedItem().getName();
    }

    private static <T> T find(Component component, Class<T> type) {
        if (type.isInstance(component)) return type.cast(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Lets the Swing steps and the file work queued by a change run. */
    private static void settle() throws Exception {
        for (int step = 0; step < 3; step++) SwingUtilities.invokeAndWait(() -> { });
        Thread.sleep(300);
        SwingUtilities.invokeAndWait(() -> { });
    }
}
