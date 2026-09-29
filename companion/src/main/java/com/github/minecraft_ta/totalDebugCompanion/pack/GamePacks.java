package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackFolders;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipFile;

/**
 * The packs the game uses: the resource packs and the current world's datapacks the connected game names, in its order,
 * and, without them, what {@code options.txt} and a world's {@code level.dat} enable. It answers which pack's copy of a
 * file the game uses, for the views and the edits that show whether a copy is used.
 */
public final class GamePacks {
    private final GameLocation location;
    private final Path workspace;
    private volatile PackStackPayload stack;
    /** What the game played when it named {@link #stack}, whose packs they are. */
    private volatile PlayingPayload stackFor;
    /** Run whenever the game names its packs again, such as after another world opened. */
    private final List<Runnable> stackListeners = new CopyOnWriteArrayList<>();

    /** The packs of the game {@code location} tells of. */
    public GamePacks(GameLocation location) {
        this.location = Objects.requireNonNull(location, "location");
        this.workspace = location.workspace();
        location.addListener(change -> {
            if (change == GameLocation.Change.DISCONNECTED) gameDisconnected();
            else if (change == GameLocation.Change.PLAYING) {
                // The packs the game named belong to what it played; it names them again for what it plays now.
                if (!Objects.equals(this.stackFor, location.playing())) this.stack = null;
                this.stackListeners.forEach(Runnable::run);
            }
        });
    }

    /** Where the game whose packs these are is. */
    public GameLocation location() {
        return this.location;
    }

    /** The game disconnected: the packs it named no longer hold. */
    private void gameDisconnected() {
        this.stack = null;
        this.stackListeners.forEach(Runnable::run);
    }

    /** Takes the game's enabled packs. */
    public void packStack(PackStackPayload stack) {
        this.stackFor = this.location.playing();
        this.stack = stack;
        this.stackListeners.forEach(Runnable::run);
    }

    /** The packs the running game named last, or null while no game is connected. */
    public PackStackPayload packStack() {
        return this.stack;
    }

    /**
     * Runs {@code listener} whenever the game names its packs again, says what it plays, or disconnects, on the thread
     * that saw it; returns its removal.
     */
    public Runnable addStackListener(Runnable listener) {
        this.stackListeners.add(listener);
        return () -> this.stackListeners.remove(listener);
    }

    /**
     * Why the game does not use {@code pack}'s copy of {@code path}: an enabled pack above it that supplies the path too,
     * or the pack not being enabled; empty when it uses it, or the game has not named its packs. The managed pack is
     * enabled by the reload after a save, so only the packs above it count. Blocking.
     */
    public Optional<String> unusedBecause(String path, Path pack) {
        boolean assets = path.startsWith("assets/");
        PackStackPayload current = this.stack;
        // The game names the open world's datapacks only; another world's and a closed game's are read from their files.
        GameState game = this.location.read();
        if (current == null || !assets && !game.plays(pack.getParent().getParent())) return disabledOnDisk(game, path, pack);
        List<PackStackPayload.Pack> packs = assets ? current.resourcePacks() : current.dataPacks();
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
        PackStackPayload current = this.stack;
        if (current == null) return Optional.empty();
        for (String path : beside) {
            List<PackStackPayload.Pack> packs = path.startsWith("assets/") ? current.resourcePacks() : current.dataPacks();
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
        PackStackPayload current = this.stack;
        if (current != null) {
            List<PackStackPayload.Pack> packs = path.startsWith("assets/") ? current.resourcePacks() : current.dataPacks();
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
        if (Files.isDirectory(source)) {
            Path file = source.resolve(path);
            if (Files.size(file) > limit) throw new IOException(path + " is larger than " + limit / 1024 + " KiB");
            return Files.readAllBytes(file);
        }
        try (ZipFile zip = new ZipFile(source.toFile())) {
            var entry = zip.getEntry(path);
            if (entry == null) throw new IOException(path + " is gone");
            if (entry.getSize() > limit) throw new IOException(path + " is larger than " + limit / 1024 + " KiB");
            try (var input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(limit + 1);
                if (bytes.length > limit) throw new IOException(path + " is larger than " + limit / 1024 + " KiB");
                return bytes;
            }
        }
    }

    private static boolean contains(Path source, String path) {
        if (Files.isDirectory(source)) return Files.isRegularFile(source.resolve(path));
        if (!Files.isRegularFile(source)) return false;
        try (ZipFile zip = new ZipFile(source.toFile())) {
            return zip.getEntry(path) != null;
        } catch (IOException unreadable) {
            return false;
        }
    }
}
