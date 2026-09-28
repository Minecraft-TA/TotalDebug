package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.LevelDat;
import com.github.minecraft_ta.totalDebugCompanion.catalog.Worlds;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.GameRulesPayload;
import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Sets a world's game rules: in the world the connected game has open, as {@code /gamerule} does, or in the
 * {@code level.dat} of a world no game has open, keeping the last one as {@code level.dat_old} as the game does. Each
 * change is recorded as a {@link ChangeRecord.GameRule} and can be reverted.
 */
public final class GameRuleEdits {
    private static final Pattern NUMBER = Pattern.compile("-?\\d+");

    /** Where a rule was written, and when the game uses it. */
    public record Applied(ConfigChanges.Effect effect) {
    }

    private final ChangeRecord record;
    private final ResourceEdits edits;
    private final Executor writes;

    /** {@code edits} talks to the connected game; {@code writes} is the project's write queue. */
    public GameRuleEdits(ChangeRecord record, ResourceEdits edits, Executor writes) {
        this.record = Objects.requireNonNull(record, "record");
        this.edits = Objects.requireNonNull(edits, "edits");
        this.writes = Objects.requireNonNull(writes, "writes");
    }

    /** Sets {@code name} of {@code world} to {@code value} and records the change. */
    public CompletableFuture<Applied> set(Path world, String name, String value) {
        return apply(new ChangeRecord.GameRule(world, name), value, null);
    }

    /**
     * Sets the rule back to what it was before Companion first changed it. A rule changed since outside Companion, such
     * as with {@code /gamerule}, is left alone, unless it is the original again, which ends the change.
     */
    public CompletableFuture<Applied> revert(ChangeRecord.Change change) {
        if (!(change.target() instanceof ChangeRecord.GameRule target)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Not a game rule"));
        }
        return apply(target, change.original(), change);
    }

    /**
     * Whether the rule still has the value Companion last set for {@code change}. A rule set back to its original outside
     * Companion ends the change, but only as a closed world's level.dat says: the game's rules may be a moment old, and
     * a change ended from them would lose its revert. Blocking.
     */
    public boolean holds(ChangeRecord.Change change) {
        if (!(change.target() instanceof ChangeRecord.GameRule target)) return true;
        try {
            String current = current(target);
            if (current == null) return true;
            if (!Worlds.isOpen(target.world())) this.record.observed(target, current, String::equals);
            return change.current().equals(current);
        } catch (IOException unreadable) {
            return true;
        }
    }

    /** What a set reads before it writes: the rule's value, the value to write, and whether the connected game sets it. */
    private record Read(String previous, String written, boolean live) {
    }

    private CompletableFuture<Applied> apply(ChangeRecord.GameRule target, String value, ChangeRecord.Change reverting) {
        // Whether the world is open is read with the rest, in the write queue, never on the Swing thread.
        CompletableFuture<Read> before = write(() -> {
            try {
                boolean live = live(target.world());
                String previous = current(target);
                if (previous == null) throw new IOException("The world " + target.world().getFileName() + " has no game rule " + target.name());
                String problem = problem(previous, value);
                if (problem != null) throw new IOException(problem);
                String written = canonical(previous, value);
                if (reverting != null && !previous.equals(reverting.current()) && !previous.equals(written)) {
                    throw new IOException(target.name() + " was changed outside Companion since, and reverting would replace that");
                }
                if (!live) {
                    writeLevel(target, previous, written);
                    this.record.changed(target, previous, written);
                }
                return new Read(previous, written, live);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
        return before.thenCompose(read -> {
            if (!read.live()) return CompletableFuture.completedFuture(new Applied(ConfigChanges.Effect.WORLD_OPENS));
            // The game sets it only while the world and the rule's value are still those read here.
            // Recorded whenever the game answers, even after the caller stopped waiting, so a rule the game set keeps its revert.
            CompletableFuture<Applied> recorded = this.edits.setGameRule(target.world().getFileName().toString(), target.name(),
                    read.previous(), read.written()).thenApply(result -> {
                if (!result.error().isEmpty()) throw new CompletionException(new IOException(result.error()));
                // The game names its rules again only on its next check; the next set reads the value set now.
                this.edits.gameRuleSet(target.name(), read.written());
                this.record.changed(target, read.previous(), read.written());
                return new Applied(ConfigChanges.Effect.NOW);
            });
            return recorded.copy().orTimeout(ResourceEdits.RELOAD_MINUTES, TimeUnit.MINUTES);
        });
    }

    /**
     * Whether the connected game sets the rule; fails for a world open in a game Companion is not connected to, whose
     * server would write its {@code level.dat} over.
     */
    private boolean live(Path world) throws IOException {
        if (!Worlds.isOpen(world)) return false;
        GameRulesPayload rules = this.edits.gameRules();
        if (rules != null && rules.of(world.getFileName().toString())) return true;
        throw new IOException("The world " + world.getFileName()
                + " is open in a game that is not connected to Companion; connect it, or close the world, to change its game rules");
    }

    /** The rule's value: as the connected game names it while it has the world open, or as level.dat saved it; null for none. */
    String current(ChangeRecord.GameRule target) throws IOException {
        GameRulesPayload rules = this.edits.gameRules();
        // The connected game's rules count only for the world it names, not another one open in another game. While a game
        // has the world open, its level.dat lags behind it, so without the game's rules the value is unknown.
        if (Worlds.isOpen(target.world())) {
            if (rules != null && rules.of(target.world().getFileName().toString())) return rules.rules().get(target.name());
            throw new IOException("The world " + target.world().getFileName() + " is open in a game that is not connected to Companion");
        }
        NbtData.CompoundTag gameRules = gameRules(LevelDat.read(LevelDat.file(target.world())).tag());
        return gameRules != null && gameRules.entries().get(target.name()) instanceof NbtData.StringTag text ? text.value() : null;
    }

    /** Why {@code value} does not fit a rule whose value is {@code previous}, as the game's command would refuse it, or null. */
    public static String problem(String previous, String value) {
        boolean flag = previous.equals("true") || previous.equals("false");
        if (flag && !value.equals("true") && !value.equals("false")) return "Enter true or false";
        if (NUMBER.matcher(previous).matches()) {
            if (!NUMBER.matcher(value).matches()) return "Enter a whole number";
            try {
                Integer.parseInt(value);
            } catch (NumberFormatException tooLarge) {
                return "Enter a whole number between " + Integer.MIN_VALUE + " and " + Integer.MAX_VALUE;
            }
        }
        if (value.isBlank() || value.contains("\n")) return "Enter a value";
        return null;
    }

    /** {@code value} as the game writes it: a whole number without leading zeros or a sign on zero. */
    static String canonical(String previous, String value) {
        return NUMBER.matcher(previous).matches() ? Integer.toString(Integer.parseInt(value)) : value;
    }

    /** Writes {@code value} into the world's level.dat while it still holds {@code previous}, holding the world's lock. */
    private static void writeLevel(ChangeRecord.GameRule target, String previous, String value) throws IOException {
        LevelDat.update(target.world(), root -> {
            if (!(root.tag().entries().get("Data") instanceof NbtData.CompoundTag data)) {
                throw new IOException("The level.dat of " + target.world().getFileName() + " holds no world data");
            }
            NbtData.CompoundTag rules = gameRules(root.tag());
            if (rules == null || !(rules.entries().get(target.name()) instanceof NbtData.StringTag held) || !held.value().equals(previous)) {
                throw new IOException(target.name() + " changed in the world since it was read");
            }
            NbtData.CompoundTag written = LevelDat.with(rules, target.name(), new NbtData.StringTag(value));
            return new LevelDat.Root(root.name(), LevelDat.with(root.tag(), "Data", LevelDat.with(data, "GameRules", written)));
        });
    }

    private static NbtData.CompoundTag gameRules(NbtData.CompoundTag root) {
        return root.entries().get("Data") instanceof NbtData.CompoundTag data
                && data.entries().get("GameRules") instanceof NbtData.CompoundTag rules ? rules : null;
    }

    private <T> CompletableFuture<T> write(Supplier<T> write) {
        try {
            return CompletableFuture.supplyAsync(write, this.writes);
        } catch (RejectedExecutionException closed) {
            return CompletableFuture.failedFuture(new IOException("The project is closing; the change was not written"));
        }
    }
}
