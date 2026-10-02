package com.github.minecraft_ta.totalDebugCompanion.project;

import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.MessageRoutes;
import com.github.minecraft_ta.totaldebug.protocol.message.ClientPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PreparedFilePayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.DebugTargetMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.KeyAssignmentsMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.PackStackMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.PreparedFileMessage;
import com.github.tth05.scnet.message.AbstractMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** A project's owners take the game's messages through their own routes, and only while the project is the current one. */
class ProjectScopeMessagesTest {
    @TempDir Path directory;

    @Test
    void aProjectTakesItsMessagesUntilAnotherBecomesCurrent() throws Exception {
        Routes routes = new Routes();
        ProjectScope scope = ProjectScope.open(new Object(), CompanionProfile.forGame(this.directory));
        try {
            Runnable stop = scope.listen(routes);
            PackStackPayload faithful = stack("file/Faithful");
            routes.deliver(new PackStackMessage(new ClientPacksPayload(faithful, 48)));
            assertEquals(faithful, scope.packs().resourcePacks(), "the packs the game named reach the project's packs");

            // Another project becomes the current one.
            stop.run();
            routes.deliver(new PackStackMessage(new ClientPacksPayload(stack("file/Other"), 48)));
            assertEquals(faithful, scope.packs().resourcePacks(), "a project that is not current takes nothing");
            assertEquals(0, routes.handlers.size(), "and leaves no route behind");
        } finally {
            scope.retire();
            scope.close();
        }
    }

    @Test
    void theGameSavingChangedKeysHasTheKeysReadAgain() throws Exception {
        Routes routes = new Routes();
        ProjectScope scope = ProjectScope.open(new Object(), CompanionProfile.forGame(this.directory));
        try {
            Path options = scope.profile().workspaceDirectory().resolve("options.txt");
            Files.writeString(options, "key_key.jump:key.keyboard.space\n");
            Runnable stop = scope.listen(routes);
            AtomicInteger told = new AtomicInteger();
            scope.keyBindings().assignmentsChanged().subscribe(told::incrementAndGet);
            assertEquals("key.keyboard.space", scope.keyBindings().assignments().get("key.jump").encode());

            // The player rebinds jump in the game's controls screen, which saves options.txt as it closes.
            Files.writeString(options, "key_key.jump:key.keyboard.g\n");
            routes.deliver(new KeyAssignmentsMessage());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (told.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, told.get(), "the Key bindings page and the change labels hear of it");
            assertEquals("key.keyboard.g", scope.keyBindings().assignments().get("key.jump").encode());
            stop.run();
        } finally {
            scope.retire();
            scope.close();
        }
    }

    @Test
    void thePackCatalogAndTheGamesProcessReachTheCurrentProject() throws Exception {
        Routes routes = new Routes();
        ProjectScope scope = ProjectScope.open(new Object(), CompanionProfile.forGame(this.directory));
        try {
            Runnable stop = scope.listen(routes);
            routes.deliver(new PreparedFileMessage(PreparedFilePayload.preparing(PreparedFilePayload.Kind.PACK_CATALOG, "inventory", "Capturing")));
            assertInstanceOf(PackCatalogService.Capturing.class, scope.catalog().state(), "the game captures the catalog");
            routes.deliver(new PreparedFileMessage(PreparedFilePayload.failed(PreparedFilePayload.Kind.RUNTIME_INVENTORY, "inventory", "Not this one")));
            assertInstanceOf(PackCatalogService.Capturing.class, scope.catalog().state(), "another prepared file is not the catalog");
            routes.deliver(new PreparedFileMessage(PreparedFilePayload.failed(PreparedFilePayload.Kind.PACK_CATALOG, "inventory", "No catalog")));
            assertEquals(new PackCatalogService.Failed("No catalog"), scope.catalog().state());

            scope.location().connected(message -> true);
            routes.deliver(new DebugTargetMessage("game", "Minecraft", DebugTargetMessage.LOCAL_JVM, 4242));
            assertEquals(4242, scope.location().process(), "the debug target names the game's process");

            stop.run();
            routes.deliver(new PreparedFileMessage(PreparedFilePayload.preparing(PreparedFilePayload.Kind.PACK_CATALOG, "inventory", "Capturing")));
            assertEquals(new PackCatalogService.Failed("No catalog"), scope.catalog().state(), "a project that is not current takes nothing");
        } finally {
            scope.retire();
            scope.close();
        }
    }

    private static PackStackPayload stack(String pack) {
        return new PackStackPayload(34, List.of(new PackStackPayload.Pack(pack, pack, "")));
    }

    /** Routes that deliver what the test hands them, as the connection delivers what the game sends. */
    private static final class Routes implements MessageRoutes {
        private record Handler(Class<?> type, Consumer<Object> handler) {
        }

        final List<Handler> handlers = new ArrayList<>();

        @Override
        public <M extends AbstractMessage> Runnable on(Class<M> type, Consumer<M> handler) {
            Handler added = new Handler(type, message -> handler.accept(type.cast(message)));
            this.handlers.add(added);
            return () -> this.handlers.remove(added);
        }

        void deliver(AbstractMessage message) {
            for (Handler handler : List.copyOf(this.handlers)) {
                if (handler.type().isInstance(message)) handler.handler().accept(message);
            }
        }
    }
}
