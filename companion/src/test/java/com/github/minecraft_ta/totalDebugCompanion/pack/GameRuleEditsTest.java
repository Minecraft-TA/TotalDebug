package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.catalog.LevelDatFixture;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totaldebug.protocol.message.GameRulesPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetGameRulePayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.SetGameRuleMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameRuleEditsTest {
    @TempDir Path directory;

    @Test
    void aClosedWorldsRuleIsWrittenIntoItsLevelDatAndRevertedFromThere() throws Exception {
        Path world = world();
        ChangeRecord record = ChangeRecord.inMemory();
        GameRuleEdits rules = new GameRuleEdits(record, edits(record), Runnable::run);

        assertEquals(ConfigChanges.Effect.WORLD_OPENS, rules.set(world, "keepInventory", "false").get(5, TimeUnit.SECONDS).effect());
        CurrentWorld.Saved saved = CurrentWorld.read(world);
        assertEquals("false", saved.gameRules().get("keepInventory"));
        assertEquals("3", saved.gameRules().get("randomTickSpeed"), "the other rules stay");
        assertTrue(Files.isRegularFile(world.resolve("level.dat_old")), "the last level.dat is kept as the backup");
        ChangeRecord.Change change = record.changes().getFirst();
        assertEquals("true", change.original());
        assertTrue(rules.holds(change));

        rules.revert(change).get(5, TimeUnit.SECONDS);
        assertEquals("true", CurrentWorld.read(world).gameRules().get("keepInventory"));
        assertEquals(0, record.size());
    }

    @Test
    void aValueTheGameWouldRefuseIsNotWritten() throws Exception {
        Path world = world();
        ChangeRecord record = ChangeRecord.inMemory();
        GameRuleEdits rules = new GameRuleEdits(record, edits(record), Runnable::run);

        assertRefused("Enter true or false", () -> rules.set(world, "keepInventory", "maybe"));
        assertRefused("Enter a whole number", () -> rules.set(world, "randomTickSpeed", "fast"));
        assertRefused("has no game rule doesNotExist", () -> rules.set(world, "doesNotExist", "1"));
        assertEquals(0, record.size());
        assertFalse(Files.exists(world.resolve("level.dat_old")), "level.dat was not written");
        assertNull(GameRuleEdits.problem("3", "-7"));
        assertEquals("3", GameRuleEdits.canonical("5", "03"), "as the game writes it");
        assertEquals("0", GameRuleEdits.canonical("5", "-0"));
        assertEquals("true", GameRuleEdits.canonical("false", "true"));
    }

    @Test
    void theConnectedGameSetsTheRuleOfTheWorldItHasOpen() throws Exception {
        Path world = world();
        Files.writeString(world.resolve("session.lock"), "x");
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = edits(record);
        List<SetGameRulePayload> sent = new CopyOnWriteArrayList<>();
        edits.gameConnected(message -> {
            if (message instanceof SetGameRuleMessage rule) sent.add(rule.payload());
            return true;
        });
        GameRuleEdits rules = new GameRuleEdits(record, edits, Runnable::run);

        try (FileChannel channel = FileChannel.open(world.resolve("session.lock"), StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            assertRefused("is open in a game that is not connected", () -> rules.set(world, "keepInventory", "false"));

            edits.gameRules(new GameRulesPayload(Map.of("keepInventory", "true", "randomTickSpeed", "3")));
            CompletableFuture<GameRuleEdits.Applied> applied = rules.set(world, "randomTickSpeed", "10");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (sent.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(new SetGameRulePayload(sent.getFirst().requestId(), "Test", "randomTickSpeed", "3", "10"), sent.getFirst(),
                    "for this world, while the rule is still what was read");
            edits.answered(new ReloadResultPayload(sent.getFirst().requestId(), 1, List.of(), ""));
            assertEquals(ConfigChanges.Effect.NOW, applied.get(5, TimeUnit.SECONDS).effect());
            assertEquals("3", record.changes().getFirst().original(), "the value the running game had");
        }
        assertEquals("3", CurrentWorld.read(world).gameRules().get("randomTickSpeed"), "the game saves level.dat itself");
    }

    private Path world() throws Exception {
        Path world = this.directory.resolve("saves/Test");
        LevelDatFixture.write(world, LevelDatFixture.world("Test"));
        return world;
    }

    private ResourceEdits edits(ChangeRecord record) {
        return new ResourceEdits(this.directory, record, new ResourceOriginals(this.directory.resolve("total-debug/originals")),
                Runnable::run, () -> false, InstanceState.inMemory());
    }

    private interface Attempt {
        CompletableFuture<?> run() throws Exception;
    }

    private static void assertRefused(String message, Attempt attempt) {
        ExecutionException refused = assertThrows(ExecutionException.class, () -> attempt.run().get(5, TimeUnit.SECONDS));
        assertTrue(refused.getCause().getMessage().contains(message), refused.getCause().getMessage());
    }
}
