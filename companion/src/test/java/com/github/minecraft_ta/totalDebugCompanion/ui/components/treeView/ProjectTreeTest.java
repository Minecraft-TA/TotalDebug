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
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyTreeNode;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.WindowFocus;
import com.github.minecraft_ta.totalDebugCompanion.util.Workers;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import javax.swing.tree.TreeNode;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project tree as the window shows it (docs/PROJECT_TREE.md, finish line): its roots as the project's state gives
 * them, Show in Project reaching every kind of row, and which rows load again when that state changes.
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
            if (Files.isDirectory(game.resolve("mods"))) {
                // The mods are indexed when the project opens; the binding that brings replaces the Runtime root.
                UiTestScope.await(() -> app.currentScope().runtime() != null
                        && app.getRuntimeIndexStatus().phase() == RuntimeIndexService.Phase.READY);
            }
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

            assertReveals(tree, () -> tree.revealPackConfiguration(() -> true), ModTreeItems.CONFIGURATION);
            assertReveals(tree, () -> tree.revealPackResources(() -> true), ModTreeItems.RESOURCES);
            assertReveals(tree, () -> tree.revealKeyBindings(() -> true), ModTreeItems.KEY_BINDINGS);
            assertReveals(tree, () -> tree.revealLogs(() -> true), ModTreeItems.LOGS);
            assertReveals(tree, () -> tree.revealChanges(() -> true), ModTreeItems.CHANGES);
            assertReveals(tree, () -> tree.revealContent("", () -> true), ModTreeItems.CONTENT);
            assertReveals(tree, () -> tree.revealModPage(new NavigationTarget.ModPage("testmod"), () -> true), "testmod");
            assertReveals(tree, () -> tree.revealModPage(new NavigationTarget.ModPage("testmod", ModTab.CONFIGURATION, ""), () -> true),
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

            assertReveals(tree, () -> tree.revealWorld(WorldTab.OVERVIEW, () -> true), WorldTreeItems.ROOT);
            assertReveals(tree, () -> tree.revealWorld(WorldTab.GAME_RULES, () -> true), WorldTreeItems.rowName(WorldTab.GAME_RULES));
            assertReveals(tree, () -> tree.revealWorld(WorldTab.DATAPACKS, () -> true), WorldTreeItems.rowName(WorldTab.DATAPACKS));
        });
    }

    @Test
    void theFirstChangeRecordedShowsTheChangesRow() throws Exception {
        withWindow(game -> { }, (app, window, tree, game) -> {
            app.currentScope().changes().changed(new ChangeRecord.Setting("testmod", "testmod-common.toml", game.resolve("config/testmod-common.toml"), "widgets.speed"), "4", "8");
            assertReveals(tree, () -> tree.revealChanges(() -> true), ModTreeItems.CHANGES);
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
            assertReveals(tree, () -> tree.revealWorld(WorldTab.GAME_RULES, () -> true), WorldTreeItems.rowName(WorldTab.GAME_RULES));
        });
    }

    @Test
    void showInProjectReachesAScriptFolderAndAScript() throws Exception {
        withWindow(game -> { }, (app, window, tree, game) -> {
            Path folder = Files.createDirectories(app.currentScope().scriptFiles().root().resolve("tools/nested"));
            Path script = Files.writeString(folder.resolve("Gear.tdscript"), "return 1;");
            assertTrue(tree.revealLocalPath(folder, () -> true).get(10, TimeUnit.SECONDS), "the folder is revealed");
            assertTrue(tree.revealLocalPath(script.getParent(), () -> true).get(10, TimeUnit.SECONDS));
            assertEquals("nested", UiTestScope.onEdt(() -> selectedName(tree)));
        });
    }

    @Test
    void anotherWorldLoadsOnlyTheWorldRowsAndOnlyWhenTheyDiffer() throws Exception {
        withWindow(game -> {
            LevelDatFixture.write(game.resolve("saves/First"), LevelDatFixture.world("First"));
            Map<String, Object> fewer = LevelDatFixture.world("Fewer");
            ((Map<?, ?>) fewer.get("GameRules")).remove("keepInventory");
            LevelDatFixture.write(game.resolve("saves/Fewer"), fewer);
            LevelDatFixture.write(game.resolve("saves/Alike"), LevelDatFixture.world("Alike"));
            modJar(game);
            scriptIn(game);
        }, (app, window, tree, game) -> {
            ProjectScope scope = app.currentScope();
            withCatalog(scope);
            scope.location().connected(message -> true);
            play(scope, game.resolve("saves/First"));
            expandEverything(scope, tree);
            Map<List<String>, Integer> before = loads(tree);

            play(scope, game.resolve("saves/Fewer"));
            assertEquals(Map.of(List.of(WorldTreeItems.ROOT), 1), loadedSince(tree, before),
                    "a world with other counts loads the World rows once, and nothing else");

            play(scope, game.resolve("saves/First"));
            before = loads(tree);
            play(scope, game.resolve("saves/Alike"));
            assertEquals(Map.of(), loadedSince(tree, before), "a world counted alike loads nothing");
        });
    }

    @Test
    void joiningAServerLoadsTheWorldRowsWhenItPlaysAndWhenTheServerNamedItsDatapacks() throws Exception {
        withWindow(game -> {
            LevelDatFixture.write(game.resolve("saves/First"), LevelDatFixture.world("First"));
            modJar(game);
            scriptIn(game);
        }, (app, window, tree, game) -> {
            ProjectScope scope = app.currentScope();
            withCatalog(scope);
            scope.location().connected(message -> true);
            play(scope, game.resolve("saves/First"));
            expandEverything(scope, tree);
            Map<List<String>, Integer> before = loads(tree);

            scope.location().playing(new PlayingPayload.Multiplayer("play.example.invalid", false, true));
            UiTestScope.await(() -> tree.tree().loads(WorldTreeItems.ROOT) == before.get(List.of(WorldTreeItems.ROOT)) + 1);
            settle();
            assertTrue(UiTestScope.onEdt(() -> tree.tree().hasRootNode(WorldTreeItems.ROOT)), "the server's world keeps the root");
            scope.packs().datapacks("", new PackStackPayload(48, List.of(new PackStackPayload.Pack("server_rules", "Rules", "", 0)),
                    List.of()), "");
            UiTestScope.await(() -> rows(tree, WorldTreeItems.ROOT).contains(WorldTreeItems.rowName(WorldTab.DATAPACKS)));
            assertEquals(Map.of(List.of(WorldTreeItems.ROOT), 2), loadedSince(tree, before),
                    "the World rows load when the game plays the server and when it named its datapacks");
        });
    }

    @Test
    void comingBackShowsTheLogsAndTheWorldTheGameMadeOrRemoved() throws Exception {
        withWindow(game -> {
            modJar(game);
            scriptIn(game);
        }, (app, window, tree, game) -> {
            ProjectScope scope = app.currentScope();
            withCatalog(scope);
            expandEverything(scope, tree);
            Map<List<String>, Integer> before = loads(tree);

            // The game wrote its first log and made its first world while the user was away.
            Path log = Files.writeString(Files.createDirectories(game.resolve("logs")).resolve("latest.log"), "started\n");
            Files.createDirectories(game.resolve("saves"));
            UiTestScope.onEdt(() -> WindowFocus.returned().fire());
            UiTestScope.await(() -> rows(tree, ModTreeItems.ROOT).contains(ModTreeItems.LOGS) && tree.tree().hasRootNode(WorldTreeItems.ROOT));
            settle();
            Map<List<String>, Integer> loaded = loadedSince(tree, before);
            assertEquals(1, loaded.get(List.of(ModTreeItems.ROOT)), "the Logs row is one load of the Modpack root's rows");
            assertTrue(loaded.containsKey(List.of("scripts")), "the loaded Scripts folders are listed again");
            assertTrue(loaded.keySet().stream().allMatch(path -> path.equals(List.of(ModTreeItems.ROOT))
                    || path.getFirst().equals("scripts")), "nothing else loads: " + loaded);

            Files.delete(log);
            Files.delete(game.resolve("saves"));
            UiTestScope.onEdt(() -> WindowFocus.returned().fire());
            UiTestScope.await(() -> !rows(tree, ModTreeItems.ROOT).contains(ModTreeItems.LOGS) && !tree.tree().hasRootNode(WorldTreeItems.ROOT));
        });
    }

    @Test
    void aChangeRecordedWhileTheModsRowLoadsLetsThatLoadFinish() throws Exception {
        withWindow(game -> modJar(game), (app, window, tree, game) -> {
            ProjectScope scope = app.currentScope();
            withCatalog(scope);
            assertReveals(tree, () -> tree.revealPackConfiguration(() -> true), ModTreeItems.CONFIGURATION);
            int rootLoads = UiTestScope.onEdt(() -> tree.tree().loads(ModTreeItems.ROOT));

            CompletableFuture<Boolean> revealed;
            try (FileWork held = FileWork.hold()) {
                revealed = UiTestScope.onEdt(() -> tree.revealModPage(new NavigationTarget.ModPage("testmod"), () -> true));
                UiTestScope.await(() -> tree.tree().loads(ModTreeItems.ROOT, ModTreeItems.MODS) == 1);
                scope.changes().changed(new ChangeRecord.Setting("testmod", "testmod-common.toml",
                        game.resolve("config/testmod-common.toml"), "widgets.speed"), "4", "8");
                UiTestScope.await(() -> tree.tree().loads(ModTreeItems.ROOT) == rootLoads + 1);
            }
            assertTrue(revealed.get(10, TimeUnit.SECONDS), "the mod is revealed");
            settle();
            assertEquals(1, UiTestScope.onEdt(() -> tree.tree().loads(ModTreeItems.ROOT, ModTreeItems.MODS)),
                    "the Mods row's load finished and did not start again");
            assertTrue(UiTestScope.onEdt(() -> rows(tree, ModTreeItems.ROOT)).contains(ModTreeItems.CHANGES));
        });
    }

    @Test
    void theCatalogFailingLoadsOnlyTheModpackRoot() throws Exception {
        withWindow(game -> {
            modJar(game);
            scriptIn(game);
        }, (app, window, tree, game) -> {
            ProjectScope scope = app.currentScope();
            withCatalog(scope);
            expandEverything(scope, tree);
            Map<List<String>, Integer> before = loads(tree);

            scope.catalog().failed("The game could not capture the catalog");
            UiTestScope.await(() -> !rows(tree, ModTreeItems.ROOT).contains(ModTreeItems.CONFIGURATION));
            settle();
            assertEquals(Map.of(List.of(ModTreeItems.ROOT), 1, List.of(ModTreeItems.ROOT, ModTreeItems.MODS), 1),
                    loadedSince(tree, before), "the Modpack root and the rows loaded below it load once, nothing else");
            assertEquals(List.of(ModTreeItems.MODS), UiTestScope.onEdt(() -> rows(tree, ModTreeItems.ROOT)), "only the runtime's mods are left");
        });
    }

    @Test
    void anUpdateOfTheRootsWhileAnArchiveLoadsLetsThatLoadFinish() throws Exception {
        withWindow(game -> {
            LevelDatFixture.write(game.resolve("saves/First"), LevelDatFixture.world("First"));
            modJar(game);
        }, (app, window, tree, game) -> {
            ProjectScope scope = app.currentScope();
            Path archive = game.resolve("mods/sample.jar");
            List<String> module = runtimePath(scope);
            assertTrue(UiTestScope.onEdt(() -> tree.revealRuntimeModule(scope.sources().modules().getFirst().id(), () -> true))
                    .get(10, TimeUnit.SECONDS));

            CompletableFuture<Boolean> revealed;
            try (FileWork held = FileWork.hold()) {
                revealed = UiTestScope.onEdt(() -> tree.revealArchivePath(archive, "pack.mcmeta", () -> true));
                UiTestScope.await(() -> tree.tree().loads(module.toArray(String[]::new)) == 1);
                withCatalog(scope);
                scope.changes().changed(new ChangeRecord.Setting("testmod", "testmod-common.toml",
                        game.resolve("config/testmod-common.toml"), "widgets.speed"), "4", "8");
                scope.location().connected(message -> true);
                play(scope, game.resolve("saves/First"));
            }
            assertTrue(revealed.get(10, TimeUnit.SECONDS), "the archive's entry is revealed");
            settle();
            assertEquals(1, UiTestScope.onEdt(() -> tree.tree().loads(module.toArray(String[]::new))), "the archive is indexed once");
        });
    }

    @Test
    void noFileAndNoLockOnTheSwingThreadWhileTheRootsAreComputed() throws Exception {
        withWindow(game -> modJar(game), (app, window, tree, game) -> {
            ProjectScope scope = app.currentScope();
            scope.changes().changed(new ChangeRecord.Setting("testmod", "testmod-common.toml",
                    game.resolve("config/testmod-common.toml"), "widgets.speed"), "4", "8");
            settle();
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // The record's writer flushing holds the record, as a change scheduling its save does.
            Thread writer = Thread.ofPlatform().start(() -> {
                synchronized (scope.changes()) {
                    locked.countDown();
                    try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
            });
            try (OwnerWork held = OwnerWork.hold()) {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                // Everything the tree follows, while the instance's folders are being read and the record is held.
                UiTestScope.onEdt(() -> WindowFocus.returned().fire());
                scope.changes().changed().fire();
                scope.catalog().changed().fire();
                scope.location().playingChanged().fire();
                FutureTask<Boolean> answered = new FutureTask<>(() -> {
                    tree.syncRoots();
                    return tree.tree().hasRootNode(ModTreeItems.ROOT);
                });
                SwingUtilities.invokeLater(answered);
                assertTrue(answered.get(5, TimeUnit.SECONDS), "the Swing thread answers, with the roots as last published");
            } finally {
                release.countDown();
                writer.join(5_000);
            }
        });
    }

    @Test
    void aRevealNoLongerWantedSelectsNothing() throws Exception {
        withWindow(game -> modJar(game), (app, window, tree, game) -> {
            withCatalog(app.currentScope());
            assertReveals(tree, () -> tree.revealPackConfiguration(() -> true), ModTreeItems.CONFIGURATION);

            boolean[] wanted = {true};
            CompletableFuture<Boolean> revealed;
            try (FileWork held = FileWork.hold()) {
                revealed = UiTestScope.onEdt(() -> tree.revealModPage(new NavigationTarget.ModPage("testmod"), () -> wanted[0]));
                UiTestScope.await(() -> tree.tree().loads(ModTreeItems.ROOT, ModTreeItems.MODS) == 1);
                // Another navigation replaced this one while the rows below the root load.
                UiTestScope.onEdt(() -> wanted[0] = false);
            }
            assertThrows(CancellationException.class, () -> {
                try { revealed.get(10, TimeUnit.SECONDS); }
                catch (ExecutionException failure) { throw failure.getCause(); }
            });
            settle();
            assertEquals(ModTreeItems.CONFIGURATION, UiTestScope.onEdt(() -> selectedName(tree)), "the selection stays");
        });
    }

    @Test
    void theFirstAndTheLastChangeAddAndRemoveTheModpackRootAlone() throws Exception {
        withWindow(game -> {
            Files.createDirectories(game.resolve("saves"));
            scriptIn(game);
        }, (app, window, tree, game) -> {
            ProjectScope scope = app.currentScope();
            assertFalse(UiTestScope.onEdt(() -> tree.tree().hasRootNode(ModTreeItems.ROOT)), "nothing for the Modpack root");
            assertTrue(UiTestScope.onEdt(() -> tree.revealLocalPath(scope.scriptFiles().root().resolve("nested"), () -> true))
                    .get(10, TimeUnit.SECONDS));
            assertTrue(UiTestScope.onEdt(() -> tree.revealWorld(WorldTab.OVERVIEW, () -> true)).get(10, TimeUnit.SECONDS));
            settle();
            Map<List<String>, Integer> before = loads(tree);

            ChangeRecord.Setting speed = new ChangeRecord.Setting("testmod", "testmod-common.toml",
                    game.resolve("config/testmod-common.toml"), "widgets.speed");
            scope.changes().changed(speed, "4", "8");
            UiTestScope.await(() -> tree.tree().hasRootNode(ModTreeItems.ROOT));
            scope.changes().observed(speed, "4", String::equals);
            UiTestScope.await(() -> !tree.tree().hasRootNode(ModTreeItems.ROOT));
            settle();
            assertEquals(Map.of(), loadedSince(tree, before), "no other root loads");
        });
    }

    /** Runs a reveal of {@code tree} and checks it selected the row named {@code name}. */
    private static void assertReveals(FileTreeView tree, Callable<CompletableFuture<Boolean>> reveal, String name) throws Exception {
        CompletableFuture<Boolean> revealed = UiTestScope.onEdt(reveal);
        assertTrue(revealed.get(10, TimeUnit.SECONDS), name + " is revealed");
        settle();
        assertEquals(name, UiTestScope.onEdt(() -> selectedName(tree)), name + " is selected");
    }

    /** The catalog of a pack with one mod, {@code testmod}, ready. */
    private void withCatalog(ProjectScope scope) throws Exception {
        CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)).write(scope.paths().catalog());
        scope.catalog().accept(CatalogFixtures.INVENTORY, scope.paths().catalog(), Runnable::run);
        settle();
    }

    /** A mod archive in the game's {@code mods} folder, which the Runtime root lists before a game runs. */
    private static void modJar(Path game) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(Files.createDirectories(game.resolve("mods")).resolve("sample.jar")))) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A script in a folder of the instance's Scripts. */
    private static void scriptIn(Path game) throws Exception {
        Path folder = Files.createDirectories(new InstancePaths(CompanionProfile.forGame(game).dataDirectory()).scripts().resolve("nested"));
        Files.writeString(folder.resolve("Selected.tdscript"), "return 1;");
    }

    /** The game plays the singleplayer world in {@code world}, and the world's reading published it. */
    private static void play(ProjectScope scope, Path world) throws Exception {
        scope.location().playing(new PlayingPayload.Singleplayer(world.toString()));
        UiTestScope.await(() -> scope.world().published().map(read -> world.equals(read.directory()) && read.saved() != null).orElse(false));
        settle();
    }

    /** Expands a folder of Scripts, the Mods row, the World rows and a Runtime archive, where the tree shows them. */
    private static void expandEverything(ProjectScope scope, FileTreeView tree) throws Exception {
        if (UiTestScope.onEdt(() -> tree.tree().hasRootNode("scripts"))) {
            assertTrue(UiTestScope.onEdt(() -> tree.revealLocalPath(scope.scriptFiles().root().resolve("nested"), () -> true)).get(10, TimeUnit.SECONDS));
            assertTrue(UiTestScope.onEdt(() -> tree.tree().revealItemPath("scripts", List.of("nested", "Selected.tdscript"), () -> true))
                    .get(10, TimeUnit.SECONDS));
        }
        assertTrue(UiTestScope.onEdt(() -> tree.revealModPage(new NavigationTarget.ModPage("testmod"), () -> true)).get(10, TimeUnit.SECONDS));
        if (UiTestScope.onEdt(() -> tree.tree().hasRootNode(WorldTreeItems.ROOT))) {
            assertTrue(UiTestScope.onEdt(() -> tree.revealWorld(WorldTab.GAME_RULES, () -> true)).get(10, TimeUnit.SECONDS));
        }
        Path archive = scope.sources().modules().isEmpty() ? null : scope.sources().sourcesForModule(scope.sources().modules().getFirst().id())
                .getFirst().path();
        if (archive != null) {
            assertTrue(UiTestScope.onEdt(() -> tree.revealArchivePath(archive, "pack.mcmeta", () -> true)).get(10, TimeUnit.SECONDS));
        }
        settle();
    }

    /** The path of the Runtime root's row of the project's first module. */
    private static List<String> runtimePath(ProjectScope scope) {
        List<String> path = new ArrayList<>(List.of("runtime"));
        path.addAll(FileTreeView.runtimeDirectoryPath(scope.sources().modules().getFirst(), List.of()));
        return path;
    }

    private static Map<List<String>, Integer> loads(FileTreeView tree) throws Exception {
        return UiTestScope.onEdt(() -> tree.tree().loads());
    }

    /** The loads per row started since {@code before} was counted, after the tree settled. */
    private static Map<List<String>, Integer> loadedSince(FileTreeView tree, Map<List<String>, Integer> before) throws Exception {
        settle();
        Map<List<String>, Integer> since = new HashMap<>();
        loads(tree).forEach((path, count) -> {
            int added = count - before.getOrDefault(path, 0);
            if (added != 0) since.put(path, added);
        });
        return since;
    }

    /** The names of the rows loaded under the row on {@code path}, from a root down. Swing thread only. */
    private static List<String> rows(FileTreeView tree, String... path) {
        TreeNode node = (TreeNode) tree.tree().getModel().getRoot();
        for (String name : path) {
            TreeNode next = null;
            for (int index = 0; index < node.getChildCount(); index++) {
                if (node.getChildAt(index) instanceof LazyTreeNode child && child.getUserObject().getName().equals(name)) next = child;
            }
            if (next == null) return List.of();
            node = next;
        }
        List<String> names = new ArrayList<>();
        for (int index = 0; index < node.getChildCount(); index++) {
            if (node.getChildAt(index) instanceof LazyTreeNode child) names.add(child.getUserObject().getName());
        }
        return names;
    }

    /** Holds every file worker, so that the tree's row loads wait until it is closed. */
    private static final class FileWork extends Held {
        static FileWork hold() throws Exception {
            FileWork held = new FileWork();
            held.fill(Workers.files());
            return held;
        }
    }

    /** Holds every owner's thread, so that readings, as the instance's folders, wait until it is closed. */
    private static final class OwnerWork extends Held {
        static OwnerWork hold() throws Exception {
            OwnerWork held = new OwnerWork();
            for (int thread = 0; thread < 4; thread++) held.fill(Workers.strand(), 1);
            return held;
        }
    }

    /** Threads of a pool held by blocked tasks, released on close. */
    private abstract static class Held implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);

        void fill(Executor pool) throws Exception {
            fill(pool, 4);
        }

        void fill(Executor pool, int threads) throws Exception {
            CountDownLatch started = new CountDownLatch(threads);
            for (int thread = 0; thread < threads; thread++) {
                pool.execute(() -> {
                    started.countDown();
                    try { this.release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
            }
            assertTrue(started.await(10, TimeUnit.SECONDS), "the pool's threads are held");
        }

        @Override
        public void close() {
            this.release.countDown();
        }
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
