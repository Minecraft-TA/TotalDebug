package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.KeyBindingResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.SetKeyBindingMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyBindingControlTest {
    @TempDir Path directory;

    @Test
    void aClosedGameGetsItsKeysInOptions() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "version:3955\nkey_key.jump:key.keyboard.space\nsoundCategory_master:1.0\n");
        ChangeRecord record = ChangeRecord.inMemory();
        KeyBindingControl control = new KeyBindingControl(options, record, () -> false);
        KeyBindings.Assignment space = new KeyBindings.Assignment("key.keyboard.space", "NONE");

        KeyBindingControl.Result jump = control.set("key.jump", space, new KeyBindings.Assignment("key.keyboard.g", "CONTROL"))
                .get(5, TimeUnit.SECONDS);
        control.set("key.drop", new KeyBindings.Assignment("key.keyboard.q", "NONE"), new KeyBindings.Assignment("key.keyboard.x", "NONE"))
                .get(5, TimeUnit.SECONDS);

        assertFalse(jump.live());
        assertEquals(space, jump.previous());
        List<String> lines = Files.readAllLines(options);
        assertEquals(List.of("version:3955", "key_key.jump:key.keyboard.g:CONTROL", "soundCategory_master:1.0",
                "key_key.drop:key.keyboard.x"), lines);
        assertEquals(space, control.original("key.jump"));
        assertEquals(new KeyBindings.Assignment("key.keyboard.q", "NONE"), control.original("key.drop"),
                "a binding without a line had the key the page showed");

        control.set("key.jump", jump.current(), space).get(5, TimeUnit.SECONDS);
        assertNull(control.original("key.jump"), "back on its original key, the change is over");
    }

    @Test
    void aGameRunningWithoutAConnectionKeepsItsOptions() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "key_key.jump:key.keyboard.space\n");
        KeyBindingControl control = new KeyBindingControl(options, ChangeRecord.inMemory(), () -> true);

        var refused = control.set("key.jump", new KeyBindings.Assignment("key.keyboard.space", "NONE"),
                new KeyBindings.Assignment("key.keyboard.g", "NONE"));

        ExecutionException failure = assertThrows(ExecutionException.class, () -> refused.get(5, TimeUnit.SECONDS));
        assertEquals("The game is running but not connected to Companion; connect it to change keys",
                failure.getCause().getMessage());
        assertEquals("key_key.jump:key.keyboard.space\n", Files.readString(options), "the running game would undo it");
        assertNull(control.original("key.jump"));
    }

    @Test
    void anAnswerAfterTheCallerStoppedWaitingIsStillRecorded() throws Exception {
        KeyBindingControl control = new KeyBindingControl(this.directory.resolve("options.txt"), ChangeRecord.inMemory(),
                () -> true);
        List<SetKeyBindingMessage> sent = new ArrayList<>();
        control.gameConnected(message -> {
            sent.add(message);
            return true;
        });

        control.set("key.jump", new KeyBindings.Assignment("key.keyboard.space", "NONE"),
                new KeyBindings.Assignment("key.keyboard.g", "NONE")).cancel(true);
        control.answered(new KeyBindingResultPayload(sent.getFirst().payload().requestId(), "key.jump",
                "key.keyboard.space", "NONE", "key.keyboard.g", "NONE", ""));

        assertEquals(new KeyBindings.Assignment("key.keyboard.space", "NONE"), control.original("key.jump"),
                "the game changed the binding, so Revert must know its key before");
    }

    @Test
    void bindingsChangedTogetherAllStayInOptions() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "version:3955\n");
        KeyBindingControl control = new KeyBindingControl(options, ChangeRecord.inMemory(), () -> false);
        List<KeyBindingControl.Change> changes = new ArrayList<>();
        for (int number = 1; number <= 9; number++) {
            changes.add(new KeyBindingControl.Change("key.hotbar." + number,
                    new KeyBindings.Assignment("key.keyboard." + number, "NONE"), new KeyBindings.Assignment(KeyBindings.UNBOUND, "NONE")));
        }

        assertEquals(Map.of(), control.setAll(changes).get(5, TimeUnit.SECONDS));
        assertEquals(10, Files.readAllLines(options).size(), "every binding kept its line");
    }

    @Test
    void aRunningGameChangesTheBindingItselfAndAnswers() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        KeyBindingControl control = new KeyBindingControl(this.directory.resolve("options.txt"), record, () -> false);
        List<SetKeyBindingMessage> sent = new ArrayList<>();
        control.gameConnected(message -> {
            sent.add(message);
            return true;
        });

        var answer = control.set("key.jump", new KeyBindings.Assignment("key.keyboard.space", "NONE"),
                new KeyBindings.Assignment("key.keyboard.g", "NONE"));
        assertEquals("key.keyboard.g", sent.getFirst().payload().key());
        control.answered(new KeyBindingResultPayload(sent.getFirst().payload().requestId(), "key.jump",
                "key.keyboard.space", "NONE", "key.keyboard.g", "NONE", ""));

        KeyBindingControl.Result result = answer.get(5, TimeUnit.SECONDS);
        assertTrue(result.live());
        assertEquals(new KeyBindings.Assignment("key.keyboard.space", "NONE"), control.original("key.jump"));
        assertFalse(Files.exists(this.directory.resolve("options.txt")), "the game writes options.txt itself");

        var refused = control.set("key.jump", result.current(), new KeyBindings.Assignment("key.keyboard.nope", "NONE"));
        control.answered(new KeyBindingResultPayload(sent.get(1).payload().requestId(), "key.jump", "", "", "", "",
                "Unknown key name: key.keyboard.nope"));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> refused.get(5, TimeUnit.SECONDS));
        assertEquals("Unknown key name: key.keyboard.nope", failure.getCause().getMessage());

        var unanswered = control.set("key.jump", result.current(), new KeyBindings.Assignment("key.keyboard.h", "NONE"));
        control.gameDisconnected();
        assertThrows(ExecutionException.class, () -> unanswered.get(5, TimeUnit.SECONDS));
    }

    @Test
    void keysAreWrittenTheWayOptionsWritesThem() {
        assertEquals("key.keyboard.g:CONTROL", new KeyBindings.Assignment("key.keyboard.g", "CONTROL").encode());
        assertEquals("key.mouse.4", new KeyBindings.Assignment("key.mouse.4", "NONE").encode());
        assertEquals(new KeyBindings.Assignment("key.keyboard.e", "SHIFT"), KeyBindings.Assignment.decode("key.keyboard.e:SHIFT"));
    }
}
