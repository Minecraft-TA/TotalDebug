package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.util.Signal;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackFolders;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.resource.ArchiveEntrySource;
import com.github.minecraft_ta.totalDebugCompanion.resource.ContentSource;
import com.github.minecraft_ta.totalDebugCompanion.resource.LocalFileSource;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.ClientPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessages;
import com.github.minecraft_ta.totaldebug.protocol.scnet.DatapacksRequestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ToServerMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipFile;

/**
 * The packs the game uses: the resource packs the connected game client names, and the datapacks the server of the world
 * it plays names, each in its order; without them, what {@code options.txt} and a world's {@code level.dat} enable. It
 * answers which pack's copy of a file the game uses, for the views and the edits that show whether a copy is used.
 *
 * <p>Each side has its own signal, fired only when its packs may differ: the resource packs whenever the game client
 * names them, and the datapacks whenever the world's server does, since each names them after a load of its resources,
 * which may change the packs' files though not their order; the datapacks also when the game goes to another world; and
 * a side whose file Companion wrote. A disconnect changes both, since the files take over from the game.
 */
public final class GamePacks {
    /**
     * What the game and its server named, as one value: a reader that needs two of them reads them from one moment.
     *
     * @param resourcePacks       the resource packs the connected game named last, or null while no game is connected
     * @param dataFormat          the datapack format of the connected game's version, or 0 while no game is connected
     * @param datapacks           the datapacks the server of the world the game plays named last, or null until it named them
     * @param datapacksFor        what the game played when its server named {@code datapacks}, whose datapacks they are
     * @param worldRefusal        why the world's server does not let the player change it, or empty; told with its datapacks
     * @param serverDatapackCount how many of {@code datapacks} the World page lists for a server, counted when they were named
     */
    public record Named(PackStackPayload resourcePacks, int dataFormat, PackStackPayload datapacks, PlayingPayload datapacksFor,
                        String worldRefusal, int serverDatapackCount) {
        static final Named NONE = new Named(null, 0, null, null, "", 0);

        /** The same, without what the server named: another world's server is asked again. */
        Named withoutDatapacks() {
            return new Named(this.resourcePacks, this.dataFormat, null, this.datapacksFor, "", 0);
        }
    }

    private final GameLocation location;
    private final Path workspace;
    /** Replaced under this owner's lock, read without it. */
    private volatile Named named = Named.NONE;
    private final Map<ChangeRecord.PackSide, Signal> changed = new EnumMap<>(Map.of(
            ChangeRecord.PackSide.RESOURCES, new Signal(),
            ChangeRecord.PackSide.DATA, new Signal()));

    /** The packs of the game {@code location} tells of. */
    public GamePacks(GameLocation location) {
        this.location = Objects.requireNonNull(location, "location");
        this.workspace = location.workspace();
        location.connectionChanged().subscribe(() -> {
            if (location.connection() == null) gameDisconnected();
        });
        location.playingChanged().subscribe(() -> {
            // The datapacks the server named belong to the world it played; its next world's server is asked.
            synchronized (this) {
                if (!Objects.equals(this.named.datapacksFor(), location.playing())) this.named = this.named.withoutDatapacks();
            }
            askForDatapacks();
            // Without datapacks named yet, the world's own files stand for them, and they are another world's now.
            tell(ChangeRecord.PackSide.DATA);
        });
    }

    /** Where the game whose packs these are is. */
    public GameLocation location() {
        return this.location;
    }

    /** The game disconnected: the packs it and its server named no longer hold. */
    private void gameDisconnected() {
        synchronized (this) {
            // The world the datapacks were named for stays, as it does for a reconnect told the same world again.
            this.named = new Named(null, 0, null, this.named.datapacksFor(), "", 0);
        }
        tell(ChangeRecord.PackSide.RESOURCES);
        tell(ChangeRecord.PackSide.DATA);
    }

    /**
     * Asks the server of the world the game plays to name its datapacks, through the game client: a singleplayer world's,
     * or a server's that has TotalDebug.
     */
    private void askForDatapacks() {
        GameLocation.Connection connection = this.location.connection();
        PlayingPayload playing = this.location.playing();
        boolean named = playing instanceof PlayingPayload.Singleplayer
                || playing instanceof PlayingPayload.Multiplayer server && server.totalDebug();
        if (connection == null || !named) return;
        connection.send(new ToServerMessage(RelayedMessages.toServer(new DatapacksRequestMessage(), 0, playing.identity())));
    }

    /** Takes the resource packs the game client names, and its version's datapack format. */
    public void named(ClientPacksPayload packs) {
        boolean format;
        synchronized (this) {
            Named before = this.named;
            format = packs.dataFormat() != before.dataFormat();
            this.named = new Named(packs.resourcePacks(), packs.dataFormat(), before.datapacks(), before.datapacksFor(),
                    before.worldRefusal(), before.serverDatapackCount());
        }
        tell(ChangeRecord.PackSide.RESOURCES);
        // The version's format stands for the datapacks' until the world's server names them.
        if (format) tell(ChangeRecord.PackSide.DATA);
    }

    /** What the game and its server named last, as one value. */
    public Named named() {
        return this.named;
    }

    /** The resource packs the connected game named last, or null while no game is connected. */
    public PackStackPayload resourcePacks() {
        return this.named.resourcePacks();
    }

    /**
     * Takes the datapacks the server of {@code world}, by its identity, names, or {@code refusal}, why it does not let the
     * player change the world, which leaves them unnamed. A server names its world by no identity, as the game client
     * names it by the address it joined. A report of a world the game no longer plays, sent before it left, is dropped: it
     * would stand for the datapacks of the world played now.
     */
    public void datapacks(String world, PackStackPayload packs, String refusal) {
        synchronized (this) {
            PlayingPayload playing = this.location.playing();
            // A report names a singleplayer world by its folder, and a server's by none; the menu plays neither.
            boolean current = world.isEmpty() ? playing instanceof PlayingPayload.Multiplayer
                    : playing != null && playing.identity().equals(world);
            if (!current) return;
            PackStackPayload named = refusal.isEmpty() ? packs : null;
            Named before = this.named;
            this.named = new Named(before.resourcePacks(), before.dataFormat(), named, playing, refusal,
                    named == null ? 0 : PackResources.serverDatapackCount(named));
        }
        tell(ChangeRecord.PackSide.DATA);
    }

    /**
     * Companion changed the packs of {@code side} on disk: it wrote their selection to {@code options.txt} or a world's
     * {@code level.dat}, as it does while the game is closed or has another world open, or it created the managed pack.
     */
    public void written(ChangeRecord.PackSide side) {
        tell(side);
    }

    /**
     * Why the server of the world the game plays does not let the player change it, or empty, as it last said; the server
     * decides each change itself.
     */
    public String worldRefusal() {
        return this.named.worldRefusal();
    }

    /**
     * How many datapacks the World page lists for the server the game plays on, as it named them last; 0 until then.
     * Counted when they were named, so that a count drawn on the Swing thread costs nothing.
     */
    public int serverDatapackCount() {
        return this.named.serverDatapackCount();
    }

    /** The datapacks the server of the world the game plays named last, or null until it named them. */
    public PackStackPayload datapacks() {
        return this.named.datapacks();
    }

    /**
     * The pack format of {@code assets} or data packs: the one the world's server named, or the connected game's version's;
     * 0 while no game is connected.
     */
    public int format(boolean assets) {
        Named now = this.named;
        if (assets) return now.resourcePacks() == null ? 0 : now.resourcePacks().format();
        return now.datapacks() != null ? now.datapacks().format() : now.dataFormat();
    }

    /** The enabled packs of {@code path}'s side as {@code named} has them, or null where the game or its server did not name them. */
    private static List<PackStackPayload.Pack> enabled(Named named, String path) {
        PackStackPayload stack = side(path) == ChangeRecord.PackSide.RESOURCES ? named.resourcePacks() : named.datapacks();
        return stack == null ? null : stack.enabled();
    }

    /** Fires whenever the packs of {@code side} may differ, on the thread that saw it. */
    public Signal changed(ChangeRecord.PackSide side) {
        return this.changed.get(Objects.requireNonNull(side, "side"));
    }

    /** The side whose packs supply {@code path}: resource packs for {@code assets/}, datapacks for the rest. */
    public static ChangeRecord.PackSide side(String path) {
        return path.startsWith("assets/") ? ChangeRecord.PackSide.RESOURCES : ChangeRecord.PackSide.DATA;
    }

    private void tell(ChangeRecord.PackSide side) {
        this.changed.get(side).fire();
    }

    /**
     * Why the game does not use {@code pack}'s copy of {@code path}: an enabled pack above it that supplies the path too,
     * or the pack not being enabled; empty when it uses it, or the game has not named its packs. The managed pack is
     * enabled by the reload after a save, so only the packs above it count. Blocking.
     */
    public Optional<String> unusedBecause(String path, Path pack) {
        boolean assets = path.startsWith("assets/");
        List<PackStackPayload.Pack> packs = enabled(this.named, path);
        // The server names the open world's datapacks only; another world's and a closed game's are read from their files.
        GameState game = this.location.read();
        if (packs == null || !assets && !game.plays(pack.getParent().getParent())) return disabledOnDisk(game, path, pack);
        int position = position(packs, pack);
        if (position < 0) {
            // The managed pack is enabled by the reload after a save, and so is a datapack the world does not know yet, as
            // /reload does. One its level.dat lists, but the open world does not use, was disabled since.
            if (ResourceEdits.managed(pack)) return Optional.empty();
            return assets || !newToTheWorld(game, pack) ? Optional.of(notEnabled(pack)) : Optional.empty();
        }
        return supplierAbove(packs, position, path)
                .map(above -> above + " is above the " + PackFolders.label(pack) + " and supplies this file too, so the game shows its copy");
    }

    /** Where a folder pack lies in the game's stack, by the id the game gives it, or -1 where it is not enabled. */
    private static int position(List<PackStackPayload.Pack> packs, Path pack) {
        String id = "file/" + pack.getFileName();
        int position = -1;
        for (int index = 0; index < packs.size(); index++) {
            if (packs.get(index).id().equals(id)) position = index;
        }
        return position;
    }

    /** The title of the highest pack above {@code position} of {@code packs} that supplies {@code path} too, or empty. */
    private static Optional<String> supplierAbove(List<PackStackPayload.Pack> packs, int position, String path) {
        for (int index = packs.size() - 1; index > position; index--) {
            PackStackPayload.Pack above = packs.get(index);
            if (!above.source().isEmpty() && contains(Path.of(above.source()), path)) return Optional.of(above.title());
        }
        return Optional.empty();
    }

    /**
     * Why a file written beside a resource, such as a texture's animation, is not the one the game uses: a pack above
     * {@code pack} that supplies it too. Empty when none does, or the game has not named its packs. Blocking.
     */
    Optional<String> besideUnused(List<String> beside, Path pack) {
        Named named = this.named;
        for (String path : beside) {
            List<PackStackPayload.Pack> packs = enabled(named, path);
            if (packs == null) continue;
            int position = position(packs, pack);
            if (position < 0) continue;
            String name = path.substring(path.lastIndexOf('/') + 1);
            Optional<String> above = supplierAbove(packs, position, path);
            if (above.isPresent()) {
                return Optional.of(above.get() + " is above the " + PackFolders.label(pack) + " and supplies " + name
                        + " too, so the game uses its copy");
            }
        }
        return Optional.empty();
    }

    /**
     * Why the game will not use {@code pack}'s copy when it next starts or loads the world: the pack not being enabled in
     * {@code options.txt}, or disabled in the world's {@code level.dat}. The managed pack is enabled when saved to.
     */
    private Optional<String> disabledOnDisk(GameState game, String path, Path pack) {
        if (ResourceEdits.managed(pack)) return Optional.empty();
        String id = "file/" + pack.getFileName();
        try {
            if (path.startsWith("assets/")) {
                return PackResources.enabledInOptions(this.workspace.resolve("options.txt")).contains(id)
                        ? Optional.empty() : Optional.of(notEnabled(pack));
            }
            boolean disabled = CurrentWorld.read(game, pack.getParent().getParent()).datapacks().stream()
                    .anyMatch(listed -> listed.id().equals(id) && listed.state() == ListedPack.State.DISABLED);
            return disabled ? Optional.of(notEnabled(pack)) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.of("Whether the game enables the " + PackFolders.label(pack) + " could not be read: " + unreadable.getMessage());
        }
    }

    /** Whether the datapack is in its world's folder but in neither of the lists its {@code level.dat} keeps. */
    private static boolean newToTheWorld(GameState game, Path pack) {
        String id = "file/" + pack.getFileName();
        try {
            return CurrentWorld.read(game, pack.getParent().getParent()).datapacks().stream()
                    .anyMatch(listed -> listed.id().equals(id) && listed.state() == ListedPack.State.NEW);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    private static String notEnabled(Path pack) {
        return "The " + PackFolders.label(pack) + " is not enabled, so the game does not use this file";
    }

    /**
     * The {@code .mcmeta} the game reads for {@code path} of {@code pack}: the one of the highest enabled pack from
     * {@code pack} up that supplies it, as the game takes a resource's metadata from its own pack or one above; with the
     * pack not in the game's stack, its own. Empty without one, and refused when larger than {@code limit}. Blocking.
     */
    public Optional<byte[]> metadata(String path, Path pack, int limit) throws IOException {
        String name = path + ".mcmeta";
        List<PackStackPayload.Pack> packs = enabled(this.named, path);
        if (packs != null) {
            int position = position(packs, pack);
            for (int index = packs.size() - 1; position >= 0 && index > position; index--) {
                String source = packs.get(index).source();
                if (!source.isEmpty() && contains(Path.of(source), name)) return Optional.of(read(Path.of(source), name, limit));
            }
        }
        return contains(pack, name) ? Optional.of(read(pack, name, limit)) : Optional.empty();
    }

    /** The bytes of {@code path} in a folder or zip pack, at most {@code limit}. */
    private static byte[] read(Path source, String path, int limit) throws IOException {
        // A zip entry's bytes are bounded as they are read, so its declared size is not needed.
        ContentSource file = Files.isDirectory(source) ? new LocalFileSource(source.resolve(path)) : new ArchiveEntrySource(source, path, -1);
        return file.read(limit);
    }

    private static boolean contains(Path source, String path) {
        if (Files.isDirectory(source)) return Files.isRegularFile(source.resolve(path));
        if (!Files.isRegularFile(source)) return false;
        try (ZipFile zip = new ZipFile(source.toFile())) {
            var entry = zip.getEntry(path);
            return entry != null && !entry.isDirectory();
        } catch (IOException unreadable) {
            return false;
        }
    }
}
