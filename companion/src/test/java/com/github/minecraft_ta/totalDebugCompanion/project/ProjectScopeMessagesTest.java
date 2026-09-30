package com.github.minecraft_ta.totalDebugCompanion.project;

import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.MessageRoutes;
import com.github.minecraft_ta.totaldebug.protocol.message.ClientPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.PackStackMessage;
import com.github.tth05.scnet.message.AbstractMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
