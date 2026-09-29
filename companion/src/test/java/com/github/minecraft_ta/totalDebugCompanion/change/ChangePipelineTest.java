package com.github.minecraft_ta.totalDebugCompanion.change;

import com.github.minecraft_ta.totalDebugCompanion.game.Access;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChangePipelineTest {
    @TempDir Path directory;

    /** Key bindings kept in a map, as a file the game takes up; a value naming {@code fail} cannot be written. */
    private static final class Held implements ChangeCategory<ChangeRecord.KeyBinding, String> {
        final Map<ChangeRecord.KeyBinding, String> file = new HashMap<>();

        @Override public String id() { return "held"; }
        @Override public String name(ChangeRecord.KeyBinding target) { return target.name(); }
        @Override public Access access(GameState game, ChangeRecord.KeyBinding target) { return new Access.Files(); }
        @Override public String text(String value) { return value; }

        @Override
        public Map<ChangeRecord.KeyBinding, String> readFile(Collection<ChangeRecord.KeyBinding> targets) {
            Map<ChangeRecord.KeyBinding, String> held = new HashMap<>();
            targets.forEach(target -> held.put(target, this.file.getOrDefault(target, "")));
            return held;
        }

        @Override
        public void writeFile(List<Write<ChangeRecord.KeyBinding, String>> writes, Consumer<ChangeRecord.KeyBinding> landed) throws IOException {
            for (Write<ChangeRecord.KeyBinding, String> write : writes) {
                if (write.value().contains("fail")) throw new IOException("The disk is full");
                this.file.put(write.target(), write.value());
                landed.accept(write.target());
            }
        }
    }

    private static final ChangeRecord.KeyBinding ANIMATION = new ChangeRecord.KeyBinding("slab.png.mcmeta");
    private static final ChangeRecord.KeyBinding TEXTURE = new ChangeRecord.KeyBinding("slab.png");

    private final Held held = new Held();
    private final ChangeRecord record = ChangeRecord.inMemory();

    @Test
    void everyTargetIsCheckedBeforeAnyIsWritten() {
        this.held.file.put(TEXTURE, "painted by hand");
        ChangePipeline pipeline = pipeline();

        ChangePipeline.Stale stale = assertThrows(ChangePipeline.Stale.class, () -> pipeline.write(this.held, List.of(
                new ChangePipeline.Edit<>(ANIMATION, "", "frames"), new ChangePipeline.Edit<>(TEXTURE, "", "red"))));

        assertEquals("slab.png changed in its file since Companion read it", stale.getMessage());
        assertEquals(Map.of(TEXTURE, "painted by hand"), this.held.file, "the animation, first in order, was not written either");
        assertEquals(0, this.record.size());
    }

    @Test
    void aTargetAlreadyHoldingItsNewValueIsOnlyRecorded() throws Exception {
        ChangePipeline pipeline = pipeline();
        pipeline.write(this.held, List.of(new ChangePipeline.Edit<>(TEXTURE, "", "red")));
        // Put back by hand; the revert finds the original there.
        this.held.file.put(TEXTURE, "");

        ChangePipeline.Outcome<ChangeRecord.KeyBinding> reverted = pipeline.write(this.held,
                List.of(new ChangePipeline.Edit<>(TEXTURE, "red", "")));

        assertEquals(List.of(new ChangePipeline.Applied<>(TEXTURE, "", "")), reverted.applied());
        assertNull(this.record.change(TEXTURE), "back on its original, the change is over");
    }

    @Test
    void aFailurePartWayLeavesWhatLandedRecordedAndNamesIt() {
        ChangePipeline pipeline = pipeline();

        IOException failure = assertThrows(IOException.class, () -> pipeline.write(this.held, List.of(
                new ChangePipeline.Edit<>(ANIMATION, "", "frames"), new ChangePipeline.Edit<>(TEXTURE, "", "fail"))));

        assertEquals("The disk is full; slab.png.mcmeta was written before that", failure.getMessage());
        assertEquals("frames", this.record.change(ANIMATION).current(), "the written animation can be reverted");
        assertNull(this.record.change(TEXTURE));
    }

    @Test
    void replacingWhateverTheTargetHoldsSkipsTheCheck() throws Exception {
        this.held.file.put(TEXTURE, "painted by hand");

        pipeline().write(this.held, List.of(new ChangePipeline.Edit<>(TEXTURE, null, "red")));

        assertEquals("red", this.held.file.get(TEXTURE));
        assertEquals("painted by hand", this.record.change(TEXTURE).original());
    }

    private ChangePipeline pipeline() {
        return new ChangePipeline(GameLocations.of(this.directory, false), this.record, Runnable::run);
    }
}
