package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangeCategory;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.change.Effect;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Enables and orders the resource packs or a world's datapacks, by the rules of {@code docs/RESOURCE_EDITING.md}, as
 * categories of the change pipeline: the resource packs in the connected game client ({@link ResourcePackSelection}), a
 * world's datapacks in the server of the world the game plays ({@link DatapackSelection}), each of which reloads what
 * that needs; or in {@code options.txt} or the world's {@code level.dat} where no game would write them over. Each change
 * is recorded as a {@link ChangeRecord.PackSelection} and can be reverted.
 */
public final class PackSelections {
    /** Where a selection was written, and when the game uses it. */
    public record Applied(Effect effect) {
    }

    private final GameLocation location;
    private final ResourceEdits edits;
    private final ChangePipeline pipeline;
    private final ResourcePackSelection resourcePacks;
    private final DatapackSelection datapacks;

    /** {@code edits} names the packs its game uses, and changes go through its pipeline. */
    public PackSelections(ResourceEdits edits) {
        this.edits = Objects.requireNonNull(edits, "edits");
        this.location = edits.location();
        this.pipeline = edits.pipeline();
        this.resourcePacks = new ResourcePackSelection(options());
        this.datapacks = new DatapackSelection();
    }

    /** The record's target for the resource packs, or for {@code world}'s datapacks. */
    public ChangeRecord.PackSelection target(ChangeRecord.PackSide side, Path world) {
        return new ChangeRecord.PackSelection(side, side == ChangeRecord.PackSide.RESOURCES ? options() : Objects.requireNonNull(world, "world"));
    }

    /**
     * Enables exactly {@code enabled}, lowest first, among the resource packs, or among {@code world}'s datapacks for the
     * data side, and records the change. What the view shows replaces whatever the game or the file holds, as the game's
     * pack screen does.
     */
    public CompletableFuture<Applied> set(ChangeRecord.PackSide side, Path world, List<String> enabled) {
        return change(new ChangePipeline.Edit<>(target(side, world), null, List.copyOf(enabled)));
    }

    /**
     * Enables what the selection held before Companion first changed it. A selection changed since outside Companion,
     * such as in the game's pack screen, is left alone, unless it is the original again, which ends the change.
     */
    public CompletableFuture<Applied> revert(ChangeRecord.Change change) {
        if (!(change.target() instanceof ChangeRecord.PackSelection target)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Not a pack selection"));
        }
        return change(new ChangePipeline.Edit<>(target, change.current(), parse(change.original())));
    }

    /**
     * Changes a selection through the pipeline: the connected game answers once it reloaded what the selection needs; a
     * closed game uses the resource packs when it next starts, and a world its datapacks when it next opens.
     */
    private CompletableFuture<Applied> change(ChangePipeline.Edit<ChangeRecord.PackSelection, List<String>> edit) {
        ChangeRecord.PackSide side = edit.target().side();
        return this.pipeline.change(category(side), List.of(edit)).thenApply(outcome -> new Applied(outcome.live() ? Effect.NOW
                : side == ChangeRecord.PackSide.RESOURCES ? Effect.GAME_STARTS : Effect.WORLD_OPENS));
    }

    private ChangeCategory<ChangeRecord.PackSelection, List<String>> category(ChangeRecord.PackSide side) {
        return side == ChangeRecord.PackSide.RESOURCES ? this.resourcePacks : this.datapacks;
    }

    /** Whether the selection is still what Companion last enabled for {@code change}. Blocking. */
    public boolean holds(ChangeRecord.Change change) {
        if (!(change.target() instanceof ChangeRecord.PackSelection target)) return true;
        try {
            return current(this.location.read(), target).equals(parse(change.current()));
        } catch (IOException unreadable) {
            return true;
        }
    }

    /**
     * The enabled packs, lowest first, as their category writes values: as the connected game or the world's server names
     * them, or as the file keeps them. Blocking.
     */
    List<String> current(GameState game, ChangeRecord.PackSelection target) throws IOException {
        if (target.side() == ChangeRecord.PackSide.RESOURCES) {
            PackStackPayload stack = this.edits.packs().resourcePacks();
            if (stack != null) {
                // As options.txt keeps them: without the parts of another pack or the packs fixed in place.
                return stack.enabled().stream().filter(pack -> !pack.is(PackStackPayload.HIDDEN) && !pack.is(PackStackPayload.FIXED))
                        .map(PackStackPayload.Pack::id).toList();
            }
            return ResourcePackSelection.asTheGameKeepsIt(PackResources.enabledInOptions(options()));
        }
        PackStackPayload datapacks = this.edits.packs().datapacks();
        if (datapacks != null && game.plays(target.location())) {
            // As the server keeps them: without the parts of the mods' pack.
            return datapacks.enabled().stream().filter(pack -> !pack.is(PackStackPayload.HIDDEN)).map(PackStackPayload.Pack::id).toList();
        }
        return DatapackSelection.saved(target.location());
    }

    static String json(List<String> ids) {
        JsonArray array = new JsonArray();
        ids.forEach(array::add);
        return array.toString();
    }

    /** The ids of a recorded selection value, lowest first. */
    public static List<String> parse(String json) {
        List<String> ids = new ArrayList<>();
        for (JsonElement id : JsonParser.parseString(json).getAsJsonArray()) ids.add(id.getAsString());
        return ids;
    }

    private Path options() {
        return this.location.workspace().resolve("options.txt");
    }
}
