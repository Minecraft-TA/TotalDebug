package com.github.minecraft_ta.totalDebugCompanion.change;

import com.github.minecraft_ta.totalDebugCompanion.game.Access;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * What a category of values tells the {@link ChangePipeline} (see {@code docs/CHANGE_PIPELINE.md}): how the game names
 * its targets, whether a change goes to the game or the file, and how the file reads and writes its values. Values are
 * text, as the change record keeps them and the game's change table reads them.
 */
public interface ChangeCategory<T extends ChangeRecord.Target> {
    /** The name the game's change table knows the category by, such as {@code keyBinding}. */
    String id();

    /** The target as the game and the user name it. */
    String name(T target);

    /** Whether a change of {@code target} goes to the connected game, into the file, or is refused. */
    Access access(GameState game, T target);

    /** The values the file holds for {@code targets}; a target the file holds nothing for is left out. Blocking. */
    Map<T, String> readFile(Collection<T> targets) throws IOException;

    /** Writes {@code values} into the file, all at once. Blocking. */
    void writeFile(Map<T, String> values) throws IOException;
}
