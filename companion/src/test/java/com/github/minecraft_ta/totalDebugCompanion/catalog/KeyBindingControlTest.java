package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.ChangePayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ChangeResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ChangeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class KeyBindingControlTest {
    private static final KeyBindings.Assignment SPACE = new KeyBindings.Assignment("key.keyboard.space", "NONE");
    private static final KeyBindings.Assignment G = new KeyBindings.Assignment("key.keyboard.g", "NONE");

    @TempDir Path directory;

    @Test
    void aClosedGameGetsItsKeysInOptions() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "version:3955\nkey_key.jump:key.keyboard.space\nsoundCategory_master:1.0\n");
        KeyBindingControl control = control(GameLocations.of(this.directory, false));
        KeyBindings.Assignment controlG = new KeyBindings.Assignment("key.keyboard.g", "CONTROL");

        assertEquals("", set(control, new KeyBindingControl.Change("key.jump", SPACE, controlG)));
        assertEquals("", set(control, new KeyBindingControl.Change("key.drop",
                new KeyBindings.Assignment("key.keyboard.q", "NONE"), new KeyBindings.Assignment("key.keyboard.x", "NONE"))));

        assertEquals(List.of("version:3955", "key_key.jump:key.keyboard.g:CONTROL", "soundCategory_master:1.0",
                "key_key.drop:key.keyboard.x"), Files.readAllLines(options));
        assertEquals(SPACE, control.original("key.jump"));
        assertEquals(new KeyBindings.Assignment("key.keyboard.q", "NONE"), control.original("key.drop"),
                "a binding without a line had the key the page showed");

        set(control, new KeyBindingControl.Change("key.jump", controlG, SPACE));
        assertNull(control.original("key.jump"), "back on its original key, the change is over");
    }

    @Test
    void aKeyChangedSinceItWasShownKeepsTheWholeChangeOut() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "key_key.jump:key.keyboard.h\nkey_key.sneak:key.keyboard.left.shift\n");
        KeyBindingControl control = control(GameLocations.of(this.directory, false));

        String refused = set(control, new KeyBindingControl.Change("key.sneak", new KeyBindings.Assignment("key.keyboard.left.shift", "NONE"), SPACE),
                new KeyBindingControl.Change("key.jump", SPACE, G));

        assertEquals("key.jump changed in its file since Companion read it", refused);
        assertEquals(List.of("key_key.jump:key.keyboard.h", "key_key.sneak:key.keyboard.left.shift"), Files.readAllLines(options),
                "neither binding moved");
        assertNull(control.original("key.sneak"));
    }

    @Test
    void aKeyWrittenWithItsNoneModifierIsTheKeyTheOptionsReaderShows() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "key_key.jump:key.keyboard.g:NONE\n");
        KeyBindingControl control = control(GameLocations.of(this.directory, false));

        assertEquals("", set(control, new KeyBindingControl.Change("key.jump", KeyBindings.readOptions(options).get("key.jump"), SPACE)));
        assertEquals(List.of("key_key.jump:key.keyboard.space"), Files.readAllLines(options));
    }

    @Test
    void aGameRunningWithoutAConnectionKeepsItsOptions() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "key_key.jump:key.keyboard.space\n");
        KeyBindingControl control = control(GameLocations.of(this.directory, true));

        assertEquals("The game is running but not connected to Companion; connect it, or close it, to change keys",
                set(control, new KeyBindingControl.Change("key.jump", SPACE, G)));
        assertEquals("key_key.jump:key.keyboard.space\n", Files.readString(options), "the running game would undo it");
        assertNull(control.original("key.jump"));
    }

    @Test
    void bindingsChangedTogetherAllStayInOptions() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "version:3955\n");
        KeyBindingControl control = control(GameLocations.of(this.directory, false));
        List<KeyBindingControl.Change> changes = new ArrayList<>();
        for (int number = 1; number <= 9; number++) {
            changes.add(new KeyBindingControl.Change("key.hotbar." + number,
                    new KeyBindings.Assignment("key.keyboard." + number, "NONE"), new KeyBindings.Assignment(KeyBindings.UNBOUND, "NONE")));
        }

        assertEquals("", control.set(changes).get(5, TimeUnit.SECONDS));
        assertEquals(10, Files.readAllLines(options).size(), "every binding kept its line");
    }

    @Test
    void aRunningGameMakesTheChangeAndAnswers() throws Exception {
        GameLocation location = GameLocations.of(this.directory, true);
        ChangePipeline pipeline = new ChangePipeline(location, ChangeRecord.inMemory(), Runnable::run);
        KeyBindingControl control = new KeyBindingControl(pipeline, listener -> () -> { });
        List<ChangePayload> sent = new ArrayList<>();
        location.connected(message -> {
            if (message instanceof ChangeMessage change) sent.add(change.payload());
            return true;
        });

        var answer = control.set(List.of(new KeyBindingControl.Change("key.jump", SPACE, G),
                new KeyBindingControl.Change("key.sneak", G, SPACE)));
        assertEquals(List.of(new ChangePayload.Edit("keyBinding", "key.jump", "key.keyboard.space", "key.keyboard.g"),
                new ChangePayload.Edit("keyBinding", "key.sneak", "key.keyboard.g", "key.keyboard.space")),
                sent.getFirst().edits(), "the bindings go as one change, with the keys they were shown on");
        pipeline.answered(new ChangeResultPayload(sent.getFirst().requestId(), List.of(
                new ChangeResultPayload.Applied("key.keyboard.space", "key.keyboard.g"),
                new ChangeResultPayload.Applied("key.keyboard.g", "key.keyboard.space")), ""));

        assertEquals("", answer.get(5, TimeUnit.SECONDS));
        assertEquals(SPACE, control.original("key.jump"));
        assertEquals(G, control.original("key.sneak"));
        assertFalse(Files.exists(this.directory.resolve("options.txt")), "the game writes options.txt itself");

        var refused = control.set(List.of(new KeyBindingControl.Change("key.jump", G, new KeyBindings.Assignment("key.keyboard.nope", "NONE"))));
        pipeline.answered(ChangeResultPayload.refused(sent.get(1).requestId(), "key.keyboard.nope is not a key of this game"));
        assertEquals("key.keyboard.nope is not a key of this game", refused.get(5, TimeUnit.SECONDS));
        assertEquals(SPACE, control.original("key.jump"), "a refused change leaves the record as it was");

        var unanswered = control.set(List.of(new KeyBindingControl.Change("key.jump", G, SPACE)));
        location.disconnected();
        assertEquals("The game disconnected before it answered", unanswered.get(5, TimeUnit.SECONDS));
    }

    @Test
    void anAnswerAfterTheCallerStoppedWaitingIsStillRecorded() throws Exception {
        GameLocation location = GameLocations.of(this.directory, true);
        ChangePipeline pipeline = new ChangePipeline(location, ChangeRecord.inMemory(), Runnable::run);
        KeyBindingControl control = new KeyBindingControl(pipeline, listener -> () -> { });
        List<ChangePayload> sent = new ArrayList<>();
        location.connected(message -> {
            if (message instanceof ChangeMessage change) sent.add(change.payload());
            return true;
        });

        control.set(List.of(new KeyBindingControl.Change("key.jump", SPACE, G))).cancel(true);
        pipeline.answered(new ChangeResultPayload(sent.getFirst().requestId(),
                List.of(new ChangeResultPayload.Applied("key.keyboard.space", "key.keyboard.g")), ""));

        assertEquals(SPACE, control.original("key.jump"), "the game changed the binding, so Revert must know its key before");
    }

    @Test
    void keysAreWrittenTheWayOptionsWritesThem() {
        assertEquals("key.keyboard.g:CONTROL", new KeyBindings.Assignment("key.keyboard.g", "CONTROL").encode());
        assertEquals("key.mouse.4", new KeyBindings.Assignment("key.mouse.4", "NONE").encode());
        assertEquals(new KeyBindings.Assignment("key.keyboard.e", "SHIFT"), KeyBindings.Assignment.decode("key.keyboard.e:SHIFT"));
    }

    private static KeyBindingControl control(GameLocation location) {
        return new KeyBindingControl(new ChangePipeline(location, ChangeRecord.inMemory(), Runnable::run), listener -> () -> { });
    }

    private static String set(KeyBindingControl control, KeyBindingControl.Change... changes) throws Exception {
        return control.set(List.of(changes)).get(5, TimeUnit.SECONDS);
    }
}
