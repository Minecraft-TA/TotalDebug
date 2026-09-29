package com.github.minecraft_ta.totalDebugCompanion.change;

import com.github.minecraft_ta.totalDebugCompanion.game.Access;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What a category of values tells the {@link ChangePipeline} (see {@code docs/CHANGE_PIPELINE.md}): how the game names
 * its targets, whether a change goes to the game or the file, and how the file reads and writes its values. A value
 * {@code V} is recorded and compared as text, as the change record keeps it and the game's change table reads it.
 */
public interface ChangeCategory<T extends ChangeRecord.Target, V> {
    /** A value to write into the file. */
    record Write<T extends ChangeRecord.Target, V>(T target, V value) {
    }

    /** The name the game's change table knows the category by, such as {@code keyBinding}. */
    String id();

    /** The target as the user names it. */
    String name(T target);

    /**
     * The world whose server owns {@code target}, whose change table the change reaches through the game client's relay;
     * null where the game client owns it.
     */
    default Path world(T target) {
        return null;
    }

    /** The target as the game's change table names it; by default as the user does. */
    default String gameTarget(T target) {
        return name(target);
    }

    /**
     * How long a change waits for the game's answer before it completes as not answered yet, and is recorded whenever the
     * game answers. A change the game answers once a reload made it take effect waits as long as a reload.
     */
    default Duration answerWait() {
        return Duration.ofSeconds(5);
    }

    /** Why a change of {@code target} was refused when its value changed since the change was made against it. */
    default String changedSince(T target) {
        return name(target) + " changed in its file since Companion read it";
    }

    /**
     * Whether a change of {@code target} goes to the connected game, into the file, or is refused. A category whose file
     * the game takes up by itself, such as by a reload, always answers with the file.
     */
    Access access(GameState game, T target);

    /**
     * Whether two values, as text, are the same value, such as two ways a file writes one; by default when they are the
     * same text.
     */
    default boolean same(String first, String second) {
        return first.equals(second);
    }

    /** {@code value} as text, as the record keeps it and the game reads it. */
    String text(V value);

    /** The values the file holds for {@code targets}, as text; a target the file holds nothing for may be left out. Blocking. */
    Map<T, String> readFile(Collection<T> targets) throws IOException;

    /**
     * Writes {@code writes} into the file, in their order, telling {@code landed} each target once its value is in place,
     * so a failure part way leaves those recorded. Runs right after {@link #readFile} of the same change, in the write
     * queue, so what that read is what the change was checked against. Blocking.
     */
    void writeFile(List<Write<T, V>> writes, Consumer<T> landed) throws IOException;
}
