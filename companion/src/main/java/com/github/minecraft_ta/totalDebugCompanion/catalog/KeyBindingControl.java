package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.util.Signal;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangeCategory;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.game.Access;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Puts key bindings on keys, as a category of the {@link ChangePipeline}: a binding is named as in {@code options.txt},
 * such as {@code key.jump}, and its value is its key as that file writes it. While the game is connected it changes the
 * binding itself, like its controls screen, and saves {@code options.txt}; otherwise Companion writes the binding's line
 * in {@code options.txt}, which the game reads when it starts. Writing that file while the game runs would be undone the
 * next time the game saves its options, so a game running without a connection is asked to connect first.
 */
public final class KeyBindingControl implements ChangeCategory<ChangeRecord.KeyBinding, String> {
    /** A change to make: the binding {@code name}, the key it had when the change was made, and its new key. */
    public record Change(String name, KeyBindings.Assignment shown, KeyBindings.Assignment assignment) {
    }

    private final ChangePipeline pipeline;
    private final Path options;
    private final KeyAssignments assignments;

    /**
     * Changes the keys of the pipeline's game, in its {@code options.txt}, whose keys {@code assignments} follows, whoever
     * writes the file.
     */
    public KeyBindingControl(ChangePipeline pipeline, KeyAssignments assignments) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.options = pipeline.location().workspace().resolve("options.txt");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
    }

    /** Fires when the keys {@code options.txt} assigns changed, such as a key rebound in the game's controls screen. */
    public Signal assignmentsChanged() {
        return this.assignments.changed();
    }

    /** The keys {@code options.txt} assigns now ({@link KeyAssignments#assignments()}). Blocking. */
    public Map<String, KeyBindings.Assignment> assignments() throws IOException {
        return this.assignments.assignments();
    }

    /** Whether others' writes of {@code options.txt} are told; without, a page reads the keys whenever it is shown. */
    public boolean assignmentsWatched() {
        return this.assignments.watched();
    }

    /** The change record the bindings' changes are entered in. */
    public ChangeRecord record() {
        return this.pipeline.record();
    }

    /** The game's {@code options.txt}, where the keys are saved. */
    public Path options() {
        return this.options;
    }

    /**
     * Puts the bindings on their keys as one change: all of them, or none when one of them had another key than shown
     * or the game refuses one. Completes, never exceptionally, with why nothing was changed, or empty.
     */
    public CompletableFuture<String> set(List<Change> changes) {
        List<ChangePipeline.Edit<ChangeRecord.KeyBinding, String>> edits = changes.stream().map(change -> new ChangePipeline.Edit<>(
                new ChangeRecord.KeyBinding(change.name()), change.shown().encode(), change.assignment().encode())).toList();
        // The game saved options.txt before it answered, or Companion wrote it: the owner of the keys reads what changed.
        return this.pipeline.change(this, edits).whenComplete((done, failure) -> this.assignments.readNow())
                .handle((done, failure) -> failure == null ? "" : message(failure));
    }

    private static String message(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /** The value a binding had before Companion first changed it, or null when Companion did not change it. */
    public KeyBindings.Assignment original(String name) {
        String original = this.pipeline.record().original(new ChangeRecord.KeyBinding(name));
        return original == null ? null : KeyBindings.Assignment.decode(original);
    }

    @Override
    public String id() {
        return "keyBinding";
    }

    @Override
    public String name(ChangeRecord.KeyBinding target) {
        return target.name();
    }

    @Override
    public Access access(GameState game, ChangeRecord.KeyBinding target) {
        return game.client("change keys");
    }

    @Override
    public String text(String value) {
        return value;
    }

    @Override
    public Map<ChangeRecord.KeyBinding, String> readFile(Collection<ChangeRecord.KeyBinding> targets) throws IOException {
        Map<ChangeRecord.KeyBinding, String> held = new HashMap<>();
        if (!Files.isRegularFile(this.options)) return held;
        for (String line : Files.readAllLines(this.options, StandardCharsets.UTF_8)) {
            for (ChangeRecord.KeyBinding target : targets) {
                String prefix = "key_" + target.name() + ":";
                // Read as the page reads it, so an unchanged key written another way, such as with :NONE, compares equal.
                if (line.startsWith(prefix)) held.put(target, KeyBindings.Assignment.decode(line.substring(prefix.length())).encode());
            }
        }
        return held;
    }

    /** Writes each binding's line of {@code options.txt}, adding the lines it lacks, in one write of the file. */
    @Override
    public void writeFile(List<Write<ChangeRecord.KeyBinding, String>> writes, Consumer<ChangeRecord.KeyBinding> landed) throws IOException {
        List<String> lines = Files.isRegularFile(this.options)
                ? new ArrayList<>(Files.readAllLines(this.options, StandardCharsets.UTF_8)) : new ArrayList<>();
        Map<ChangeRecord.KeyBinding, String> missing = new LinkedHashMap<>();
        writes.forEach(write -> missing.put(write.target(), write.value()));
        for (int index = 0; index < lines.size(); index++) {
            for (Write<ChangeRecord.KeyBinding, String> write : writes) {
                String prefix = "key_" + write.target().name() + ":";
                if (!lines.get(index).startsWith(prefix)) continue;
                lines.set(index, prefix + write.value());
                missing.remove(write.target());
            }
        }
        missing.forEach((target, value) -> lines.add("key_" + target.name() + ":" + value));
        Files.write(this.options, lines, StandardCharsets.UTF_8);
        writes.forEach(write -> landed.accept(write.target()));
    }
}
