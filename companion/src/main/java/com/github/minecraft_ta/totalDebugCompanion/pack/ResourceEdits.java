package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackFolders;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangeCategory;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.game.Access;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.resource.ResourceLoader;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReloadMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.SetPacksMessage;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

/**
 * Writes edited resources into packs, enters them in the change record and makes the game use them (see
 * docs/RESOURCE_EDITING.md). A file opened from a folder pack is saved in that pack. Any other file is saved into the
 * working pack of its side: the pack Companion manages unless another folder pack was chosen, which for assets is
 * {@code resourcepacks/TotalDebug} and for data {@code datapacks/TotalDebug} of the current world, the open one or the
 * one played last while none is open. Only the managed pack is enabled and placed on top; the player's own packs keep
 * their place. Files are written through the {@link ChangePipeline}, as a category whose value is a file's content and
 * is recorded as its hash; the game takes them up when it reloads. Reloads asked for while one runs are merged into one
 * more reload after it.
 */
public final class ResourceEdits {
    public static final String PACK_NAME = "TotalDebug";
    public static final String PACK_ID = "file/" + PACK_NAME;
    private static final long RELOAD_MINUTES = 10;

    /**
     * What a write did: when the game uses it, the pack folder it went to, and the problems
     * the game's reload logged about it, or why the reload failed in {@code reloadFailure}, empty otherwise.
     * {@code unused} says why the game does not use the pack's copy even so, such as the pack not being enabled; empty
     * when it does.
     */
    public record Saved(ConfigChanges.Effect effect, Path pack, List<String> problems, String reloadFailure, String unused) {
        public Saved {
            Objects.requireNonNull(effect, "effect");
            problems = List.copyOf(problems);
            Objects.requireNonNull(reloadFailure, "reloadFailure");
            Objects.requireNonNull(unused, "unused");
        }

        Saved(ConfigChanges.Effect effect, Path pack, List<String> problems, String reloadFailure) {
            this(effect, pack, problems, reloadFailure, "");
        }
    }

    /** Reloads to send together, to the game on one connection. */
    private static final class Batch {
        final GameLocation.Connection connection;
        /** The world whose data the batch reloads, or null while it reloads no data. */
        Path dataWorld;
        /** What the batch's data edits wait for: its result, or a failure once their world was left. */
        CompletableFuture<ReloadResultPayload> dataResult = new CompletableFuture<>();
        final Set<ReloadPayload.Kind> kinds = EnumSet.noneOf(ReloadPayload.Kind.class);
        final Set<String> watched = new LinkedHashSet<>();
        final CompletableFuture<ReloadResultPayload> result = new CompletableFuture<>();
        /**
         * Whether a save in the batch went into the managed pack of the resource packs, or of the datapacks, which the
         * game then enables on top of that stack only.
         */
        boolean managedAssets;
        boolean managedData;

        Batch(GameLocation.Connection connection) {
            this.connection = connection;
        }
    }

    private final GameLocation location;
    private final Path workspace;
    private final ChangePipeline pipeline;
    private final ChangeRecord record;
    private final ResourceOriginals originals;
    private final PackFiles files = new PackFiles();
    private final Executor writes;
    private final InstanceState state;
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<Integer, CompletableFuture<ReloadResultPayload>> waiting = new ConcurrentHashMap<>();
    private volatile PackStackPayload stack;
    /** What the game played when it named {@link #stack}, whose packs they are. */
    private volatile PlayingPayload stackFor;
    /** Run whenever the game names its packs again, such as after another world opened. */
    private final List<Runnable> stackListeners = new CopyOnWriteArrayList<>();
    /** Run after a save or revert has written its file and the game used it, or failed to. */
    private final List<Runnable> editListeners = new CopyOnWriteArrayList<>();
    /** Run when a working pack is chosen, which changes where resource tabs save. */
    private final List<Consumer<String>> workingPackListeners = new CopyOnWriteArrayList<>();
    private Batch running;
    private Batch next;
    /** Saves and reverts queued but not yet past asking for their reload; the next reload waits for them. */
    private int writing;
    /** The hash of what Companion last wrote to each resource, by the write queue, so a program's save is told from it. */
    private final Map<ChangeRecord.Resource, String> lastWritten = new ConcurrentHashMap<>();
    /** Opens resources in other programs and takes their saves; started when the first is opened. */
    private final ExternalEdits external = new ExternalEdits(this);

    /**
     * Edits the resources of the pipeline's game; {@code writes} is the project's write queue, the pipeline's, and
     * {@code state} keeps the working pack of each side.
     */
    public ResourceEdits(ChangePipeline pipeline, ResourceOriginals originals, Executor writes, InstanceState state) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.location = pipeline.location();
        this.workspace = this.location.workspace();
        this.record = pipeline.record();
        GameLocation location = this.location;
        this.originals = Objects.requireNonNull(originals, "originals");
        this.writes = Objects.requireNonNull(writes, "writes");
        this.state = Objects.requireNonNull(state, "state");
        location.addListener(change -> {
            if (change == GameLocation.Change.DISCONNECTED) gameDisconnected();
            else if (change == GameLocation.Change.PLAYING) {
                // The packs the game named belong to what it played; it names them again for what it plays now.
                if (!Objects.equals(this.stackFor, location.playing())) this.stack = null;
                dropLeftWorldData();
                this.stackListeners.forEach(Runnable::run);
            }
        });
    }

    /** Where the game of the instance is. */
    public GameLocation location() {
        return this.location;
    }

    public ChangeRecord record() {
        return this.record;
    }

    /** Opens resources in other programs, such as an image editor, and takes each save they make as a change. */
    public ExternalEdits external() {
        return this.external;
    }

    /** Stops following the files opened in other programs. */
    public void close() {
        this.external.close();
    }

    /** The game disconnected: reloads it did not answer have failed. */
    private void gameDisconnected() {
        this.stack = null;
        this.stackListeners.forEach(Runnable::run);
        for (CompletableFuture<ReloadResultPayload> request : this.waiting.values()) {
            request.completeExceptionally(new IOException("The game disconnected before it finished reloading"));
        }
        this.waiting.clear();
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
     * Runs {@code listener} after each save or revert has finished, the managed pack enabled and the game reloaded;
     * returns its removal.
     */
    public Runnable addEditListener(Runnable listener) {
        this.editListeners.add(listener);
        return () -> this.editListeners.remove(listener);
    }

    /** Tells the edit listeners once {@code edit} has finished, whether it worked or not. */
    private CompletableFuture<Saved> finished(CompletableFuture<Saved> edit) {
        return edit.whenComplete((ignored, failure) -> this.editListeners.forEach(Runnable::run));
    }

    /** Takes the game's answer to a reload. */
    public void answered(ReloadResultPayload result) {
        CompletableFuture<ReloadResultPayload> request = this.waiting.remove(result.requestId());
        if (request != null) request.complete(result);
    }

    /**
     * The pack a resource opened from a mod or another archive is written to: the working pack of its side, the pack
     * Companion manages unless another folder pack was chosen and still exists. Data belongs to a world: the open one,
     * or the one played last. Blocking.
     */
    public Path pack(String path) throws IOException {
        Path folder = packFolder(path);
        String working = this.state.workingPack(side(path));
        // Only a folder the game takes as a pack; one that lost its pack.mcmeta is not loaded.
        if (!working.isEmpty() && Files.isDirectory(folder.resolve(working)) && PackFolders.isPack(folder.resolve(working))) {
            return folder.resolve(working);
        }
        return folder.resolve(PACK_NAME);
    }

    /**
     * The folder packs a resource opened from a mod or another archive can be saved into, the managed pack first, then
     * the others of its side by name. Blocking.
     */
    public List<Path> packs(String path) throws IOException {
        Path folder = packFolder(path);
        List<Path> packs = new ArrayList<>(List.of(folder.resolve(PACK_NAME)));
        for (Path pack : PackFolders.list(folder).values()) {
            if (Files.isDirectory(pack) && !pack.getFileName().toString().equals(PACK_NAME)) packs.add(pack);
        }
        return packs;
    }

    /**
     * Saves the resources of {@code path}'s side into {@code pack} from now on, when they come from a mod or an archive,
     * and tells every open resource tab.
     */
    public void setWorkingPack(String path, Path pack) {
        String name = pack.getFileName().toString();
        this.state.setWorkingPack(side(path), name.equals(PACK_NAME) ? "" : name);
        this.workingPackListeners.forEach(listener -> listener.accept(side(path)));
    }

    /** Runs {@code listener} with the side, as {@link #side} names it, whenever a working pack is chosen; returns what removes it. */
    public Runnable addWorkingPackListener(Consumer<String> listener) {
        this.workingPackListeners.add(listener);
        return () -> this.workingPackListeners.remove(listener);
    }

    /** Whether {@code pack} is a pack Companion manages, which it creates, enables and places on top. */
    public static boolean managed(Path pack) {
        return pack.getFileName().toString().equals(PACK_NAME);
    }

    /** The folder holding the packs of {@code path}'s side: {@code resourcepacks}, or the current world's datapacks. */
    private Path packFolder(String path) throws IOException {
        if (path.startsWith("assets/")) return this.workspace.resolve("resourcepacks");
        Path world = this.location.read().currentWorld();
        if (world == null) throw new IOException("Data is written into a world's datapacks, and this game has no world yet");
        return world.resolve("datapacks");
    }

    /** The side a resource belongs to: {@code assets} or {@code data}, each with a working pack of its own. */
    public static String side(String path) {
        return path.startsWith("assets/") ? "assets" : "data";
    }

    /**
     * The folder pack holding {@code file}: a resource pack in {@code resourcepacks/} or a datapack of any world, such
     * as the player's own pack or a data file of another world's TotalDebug datapack opened from the Changes page; empty
     * for a file outside them.
     */
    public Optional<Path> packOf(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        Path resources = this.workspace.resolve("resourcepacks");
        if (normalized.startsWith(resources) && resources.relativize(normalized).getNameCount() >= 3) {
            // The game reads assets from a resource pack, never data.
            if (!resources.relativize(normalized).getName(1).toString().equals("assets")) return Optional.empty();
            Path pack = resources.resolve(resources.relativize(normalized).getName(0));
            return managed(pack) || PackFolders.isPack(pack) ? Optional.of(pack) : Optional.empty();
        }
        Path saves = this.workspace.resolve("saves");
        if (!normalized.startsWith(saves) || saves.relativize(normalized).getNameCount() < 4) return Optional.empty();
        Path pack = saves.resolve(saves.relativize(normalized).subpath(0, 3));
        // The game reads data from a datapack, never assets.
        boolean data = saves.relativize(normalized).getNameCount() > 3 && saves.relativize(normalized).getName(3).toString().equals("data");
        return data && pack.getParent().getFileName().toString().equals("datapacks") && (managed(pack) || PackFolders.isPack(pack))
                ? Optional.of(pack) : Optional.empty();
    }

    /** The copy of a resource in {@code pack}, a managed pack, or empty when it does not hold it. Blocking. */
    public Optional<byte[]> managed(Path pack, String path) throws IOException {
        Path file = pack.resolve(path);
        if (!Files.isRegularFile(file)) return Optional.empty();
        // As a tab opens a file: a copy larger than Companion reads is refused before it is read.
        long limit = path.endsWith(".png") ? ResourceLoader.MAXIMUM_PNG_BYTES : ResourceLoader.MAXIMUM_TEXT_BYTES;
        long size = Files.size(file);
        if (size > limit) {
            throw new IOException("The copy in the " + PackFolders.label(pack) + " is " + String.format(Locale.ROOT, "%.1f", size / (1024d * 1024))
                    + " MiB, more than the " + limit / (1024 * 1024) + " MiB Companion opens");
        }
        return Optional.of(Files.readAllBytes(file));
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
            if (managed(pack)) return Optional.empty();
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
    private Optional<String> besideUnused(List<String> beside, Path pack) {
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
        if (managed(pack)) return Optional.empty();
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

    /** Writes {@code content} into the managed pack of the current world and makes the game use it. */
    public CompletableFuture<Saved> save(String path, byte[] content) {
        return save(path, null, content);
    }

    /**
     * Writes {@code content} into {@code into}, a folder pack, or into the working pack of its side when it is null, and
     * makes the game use it.
     */
    public CompletableFuture<Saved> save(String path, Path into, byte[] content) {
        return save(path, into, content, Map.of());
    }

    /**
     * Saves as {@link #save(String, Path, byte[])} does, and writes each of {@code alongside}, by pack path, into the same
     * pack where it has no copy yet, such as the animation of a texture; each is a change of its own.
     */
    public CompletableFuture<Saved> save(String path, Path into, byte[] content, Map<String, byte[]> alongside) {
        return save(path, into, content, alongside, null);
    }

    /**
     * Saves as {@link #save(String, Path, byte[], Map)} does, but only over the copy whose hash is {@code expected}, empty
     * for none; another copy fails the save with {@link ChangePipeline.Stale}. A null {@code expected} replaces whatever the
     * pack holds.
     */
    public CompletableFuture<Saved> save(String path, Path into, byte[] content, Map<String, byte[]> alongside, String expected) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(alongside, "alongside");
        // The files written beside it, which the reload watches too: they may change what the game makes of the resource.
        List<String> added = new CopyOnWriteArrayList<>();
        return finished(writeAndApply(path, added, () -> {
            try {
                Path pack = into != null ? into : pack(path);
                if (managed(pack)) preparePack(pack, path.startsWith("assets/"));
                // A pack of the player's may have been removed since the tab chose it; the game would not load it.
                else if (!PackFolders.isPack(pack)) {
                    throw new IOException("The " + PackFolders.label(pack) + " is gone or has no readable pack.mcmeta, so the game does not load it");
                }
                List<ChangePipeline.Edit<ChangeRecord.Resource, byte[]>> edits = new ArrayList<>();
                // Only a copy the save makes gets them: a pack's own copy without them, such as a static texture, stays so.
                // Written first: should one fail, the resource itself is left as it was.
                if (!Files.isRegularFile(pack.resolve(path))) {
                    for (Map.Entry<String, byte[]> companion : alongside.entrySet()) {
                        // The pack's own copy, even a different one, stays.
                        if (Files.exists(pack.resolve(companion.getKey()))) continue;
                        edits.add(new ChangePipeline.Edit<>(new ChangeRecord.Resource(companion.getKey(), pack),
                                ResourceOriginals.hash(null), companion.getValue()));
                        added.add(companion.getKey());
                    }
                }
                edits.add(new ChangePipeline.Edit<>(new ChangeRecord.Resource(path, pack), expected, content));
                wrote(this.pipeline.write(this.files, edits));
                return pack;
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        })
                // A pack of the player's that is not enabled or lies below another keeps its copy unused, reloaded or not.
                .thenApply(saved -> new Saved(saved.effect(), saved.pack(), saved.problems(), saved.reloadFailure(),
                        unusedBecause(path, saved.pack()).or(() -> besideUnused(added, saved.pack())).orElse(""))));
    }

    /**
     * Puts back what the managed pack held before Companion first changed {@code change}, and makes the game use it. A
     * file changed outside Companion since is left alone, unless it holds the original again.
     */
    public CompletableFuture<Saved> revert(ChangeRecord.Change change) {
        if (!(change.target() instanceof ChangeRecord.Resource target)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Not a resource in the managed pack"));
        }
        return finished(writeAndApply(target.path(), List.of(), () -> {
            try {
                // Reverted already, such as twice from the Changes page before it refreshed.
                if (!change.equals(this.record.change(target))) return target.location();
                // A file changed outside Companion since is left alone, unless it holds the original again.
                wrote(this.pipeline.write(this.files, List.of(new ChangePipeline.Edit<>(target, change.current(),
                        this.originals.read(change.original())))));
                return target.location();
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        })).thenApply(saved -> {
            // The file is put back either way, so the change leaves the record; a pack the game skips does not use it, the
            // TotalDebug pack included.
            Path pack = saved.pack();
            return PackFolders.isPack(pack) ? saved : new Saved(saved.effect(), pack, saved.problems(),
                    saved.reloadFailure(), "The " + PackFolders.label(pack) + " is gone or has no readable pack.mcmeta, so the game does not load it");
        });
    }

    /**
     * Takes what another program saved to {@code path} in {@code pack} as a change Companion made: recorded, so Changes
     * can revert it, and used by the game as a save is. {@code before} is what the file held when the program opened it,
     * the original where neither Companion nor the program changed it since, and {@code seen} what the file held when
     * the program's save was found whole. Completes with null when the file holds what Companion or the last taken save
     * left, such as after Companion's own save, or no longer what was seen, such as while the program writes again.
     */
    public CompletableFuture<Saved> adopt(String path, Path pack, byte[] before, byte[] seen) {
        ChangeRecord.Resource target = new ChangeRecord.Resource(path, pack);
        synchronized (this) {
            this.writing++;
        }
        // Checked in the write queue, after every save and revert queued before it has written and been recorded.
        CompletableFuture<Boolean> taken = write(() -> {
            try {
                Path file = pack.resolve(path);
                String now = ResourceOriginals.hash(Files.isRegularFile(file) ? Files.readAllBytes(file) : null);
                // Changed again since it was found whole: the program's next write is taken instead.
                if (!now.equals(ResourceOriginals.hash(seen))) return false;
                ChangeRecord.Change change = this.record.change(target);
                // Without a change, what Companion wrote last is the original it put back.
                String known = change == null ? this.lastWritten.get(target) : null;
                String expected = change != null ? change.current() : known != null ? known : ResourceOriginals.hash(before);
                if (expected.equals(now)) return false;
                if (change == null) this.originals.keep(known != null ? this.originals.read(known) : before);
                this.lastWritten.put(target, now);
                this.record.changed(target, expected, now);
                return true;
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
        return finished(taken.handle((changed, failure) -> {
            try {
                if (failure != null) return CompletableFuture.<Saved>failedFuture(failure);
                if (!changed) return CompletableFuture.<Saved>completedFuture(null);
                return apply(path, List.of(), pack).thenApply(saved -> new Saved(saved.effect(), saved.pack(), saved.problems(),
                        saved.reloadFailure(), unusedBecause(path, saved.pack()).orElse("")));
            } finally {
                wrote();
            }
        }).thenCompose(Function.identity()));
    }

    /** What Companion wrote last to each target of {@code outcome}, so a program's save is told from it. */
    private void wrote(ChangePipeline.Outcome<ChangeRecord.Resource> outcome) {
        outcome.applied().forEach(applied -> this.lastWritten.put(applied.target(), applied.now()));
    }

    /**
     * Resources as a category of the pipeline: a file of a pack, whose value is its content, null for none, and is
     * recorded as its hash. The game takes a written file up when it reloads, so a change always goes into the file. The
     * content a file held before Companion first changed it is kept among the originals, so the change can be reverted.
     */
    private final class PackFiles implements ChangeCategory<ChangeRecord.Resource, byte[]> {
        @Override
        public String id() {
            return "resource";
        }

        @Override
        public String name(ChangeRecord.Resource target) {
            return target.path().substring(target.path().lastIndexOf('/') + 1);
        }

        @Override
        public String changedSince(ChangeRecord.Resource target) {
            return name(target) + " changed in the " + PackFolders.label(target.location()) + " since Companion read it";
        }

        @Override
        public Access access(GameState game, ChangeRecord.Resource target) {
            return new Access.Files();
        }

        @Override
        public String text(byte[] content) {
            return ResourceOriginals.hash(content);
        }

        @Override
        public Map<ChangeRecord.Resource, String> readFile(Collection<ChangeRecord.Resource> targets) throws IOException {
            Map<ChangeRecord.Resource, String> held = new HashMap<>();
            for (ChangeRecord.Resource target : targets) held.put(target, ResourceOriginals.hash(content(target)));
            return held;
        }

        @Override
        public void writeFile(List<Write<ChangeRecord.Resource, byte[]>> writes, Consumer<ChangeRecord.Resource> landed) throws IOException {
            for (Write<ChangeRecord.Resource, byte[]> write : writes) {
                Path file = write.target().location().resolve(write.target().path());
                if (record.change(write.target()) == null) originals.keep(content(write.target()));
                if (write.value() == null) Files.deleteIfExists(file);
                else AtomicFiles.replace(file, staged -> Files.write(staged, write.value()));
                lastWritten.put(write.target(), text(write.value()));
                landed.accept(write.target());
            }
        }

        private static byte[] content(ChangeRecord.Resource target) throws IOException {
            Path file = target.location().resolve(target.path());
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        }
    }

    /**
     * Reverts every change of {@code changes}, as {@link #revert(ChangeRecord.Change)} does, with one reload for all of
     * them however fast each is written.
     */
    public List<CompletableFuture<Saved>> revert(List<ChangeRecord.Change> changes) {
        // The batch counts as a write until every revert is queued, so the first to finish cannot reload alone.
        synchronized (this) {
            this.writing++;
        }
        try {
            List<CompletableFuture<Saved>> reverts = new ArrayList<>();
            for (ChangeRecord.Change change : changes) reverts.add(revert(change));
            return reverts;
        } finally {
            wrote();
        }
    }

    /**
     * Writes the file of {@code path} in the write queue, then tells when the game uses it; the reload also watches
     * {@code alsoWatched}, filled by the write with files written beside it. Writes queued together, such as saves in
     * quick succession, take one reload: it is asked for once the last of them has written.
     */
    private CompletableFuture<Saved> writeAndApply(String path, List<String> alsoWatched, Supplier<Path> write) {
        synchronized (this) {
            this.writing++;
        }
        return write(write).handle((pack, failure) -> {
            try {
                return failure != null ? CompletableFuture.<Saved>failedFuture(failure) : apply(path, alsoWatched, pack);
            } finally {
                wrote();
            }
        }).thenCompose(Function.identity());
    }

    /** Sends the reload the writes asked for once none is left in the queue. */
    private synchronized void wrote() {
        this.writing--;
        if (this.writing == 0 && this.running == null && this.next != null) sendNext();
    }

    /** Runs {@code write} in the project's write queue; refused once the project closes. */
    private <T> CompletableFuture<T> write(Supplier<T> write) {
        try {
            return CompletableFuture.supplyAsync(write, this.writes);
        } catch (RejectedExecutionException closed) {
            return CompletableFuture.failedFuture(new IOException("The project is closing; the change was not written"));
        }
    }

    /**
     * Whether the managed pack still holds what Companion wrote for {@code change}. A file back to its original outside
     * Companion ends the change, checked again in the write queue so a save or revert under way is never undone.
     * Blocking.
     */
    public boolean holds(ChangeRecord.Change change) {
        if (!(change.target() instanceof ChangeRecord.Resource target)) return true;
        Path file = target.location().resolve(target.path());
        String hash;
        try {
            hash = ResourceOriginals.hash(Files.isRegularFile(file) ? Files.readAllBytes(file) : null);
        } catch (IOException unreadable) {
            return true;
        }
        if (hash.equals(change.original())) {
            write(() -> {
                dropIfRestored(target, change);
                return null;
            });
        }
        return hash.equals(change.current());
    }

    /** Ends {@code change} when its file holds the original again and the record still holds that change. */
    private void dropIfRestored(ChangeRecord.Resource target, ChangeRecord.Change change) {
        if (!change.equals(this.record.change(target))) return;
        Path file = target.location().resolve(target.path());
        try {
            String hash = ResourceOriginals.hash(Files.isRegularFile(file) ? Files.readAllBytes(file) : null);
            this.record.observed(target, hash, String::equals);
            // Put back outside Companion, so what Companion wrote last is not what the file holds.
            if (this.record.change(target) == null) this.lastWritten.remove(target);
        } catch (IOException unreadable) {
            // An unreadable file keeps its change.
        }
    }

    /**
     * The problems a reload logged about {@code path} or a file written beside it; saves merged into one reload each see
     * only their own.
     */
    private static List<String> problemsOf(ReloadResultPayload result, String path, List<String> beside) {
        return result.problems().stream().filter(problem -> problem.path().equals(path) || beside.contains(problem.path()))
                .map(ReloadResultPayload.Problem::message).toList();
    }

    private static ReloadPayload.Kind kind(String path) {
        return switch (ResourcePaths.apply(path)) {
            case LANGUAGE -> ReloadPayload.Kind.LANGUAGE;
            case TEXTURE -> ReloadPayload.Kind.TEXTURES;
            case RESOURCES -> ReloadPayload.Kind.RESOURCES;
            default -> ReloadPayload.Kind.DATA;
        };
    }

    private static String message(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /** Tells when the game uses a resource written to {@code pack}, reloading what it needs when it runs. */
    private CompletableFuture<Saved> apply(String path, List<String> alsoWatched, Path pack) {
        ResourcePaths.Apply apply = ResourcePaths.apply(path);
        boolean assets = path.startsWith("assets/");
        GameState game = this.location.read();
        // Assets belong to the game. A world's datapack is read by that world only, and no game writes it, so it is written
        // whatever holds the world; only the connected game playing it uses it at once.
        Path world = assets ? null : pack.getParent().getParent();
        Access access = assets ? game.client("use the change") : game.plays(world) ? game.world(world, "use the change") : new Access.Files();
        ConfigChanges.Effect later = assets ? ConfigChanges.Effect.GAME_STARTS : ConfigChanges.Effect.WORLD_OPENS;
        if (access instanceof Access.Refused refused) {
            return CompletableFuture.completedFuture(new Saved(later, pack, List.of(), refused.reason()));
        }
        if (access instanceof Access.Files) {
            if (assets && managed(pack)) {
                try {
                    enableOffline();
                } catch (IOException | RuntimeException exception) {
                    // The file is saved either way; a malformed options.txt only keeps the pack from being enabled.
                    return CompletableFuture.completedFuture(new Saved(later, pack, List.of(),
                            "The TotalDebug pack could not be enabled in options.txt: " + exception.getMessage()));
                }
            }
            if (!assets && game.isOpen(world)) {
                // Written all the same: the game only reads a datapack, when it loads the world or its data again.
                return CompletableFuture.completedFuture(new Saved(later, pack, List.of(), "The world " + world.getFileName()
                        + (game.connected() ? " is open, and the game has not said yet that it plays it" : " is open in a game that is not connected to Companion")
                        + "; it uses the change when the world is loaded again"));
            }
            return CompletableFuture.completedFuture(new Saved(later, pack, List.of(), ""));
        }
        if (!assets && apply == ResourcePaths.Apply.WORLD_LOAD) {
            return CompletableFuture.completedFuture(new Saved(ConfigChanges.Effect.REJOIN, pack, List.of(), ""));
        }
        // Files written beside it join the same reload, which they are part of.
        GameLocation.Connection connection = ((Access.Live) access).connection();
        for (String beside : alsoWatched) reload(connection, world, kind(beside), beside, managed(pack));
        return reload(connection, world, kind(path), path, managed(pack)).handle((result, failure) -> {
            if (failure != null) {
                return new Saved(assets ? ConfigChanges.Effect.GAME_STARTS : ConfigChanges.Effect.WORLD_OPENS, pack,
                        List.of(), message(failure));
            }
            return new Saved(result.error().isEmpty() ? ConfigChanges.Effect.NOW : assets ? ConfigChanges.Effect.GAME_STARTS
                    : ConfigChanges.Effect.WORLD_OPENS, pack, problemsOf(result, path, alsoWatched), result.error());
        });
    }

    /**
     * Asks the game on {@code send} to enable exactly {@code enabled}, lowest first, among the resource packs or the
     * datapacks of {@code world}, which it must still play, and reload what that needs; completes with its answer, or fails
     * once that connection ended. {@code world} is null for resource packs.
     */
    public CompletableFuture<ReloadResultPayload> select(GameLocation.Connection send, SetPacksPayload.Side side, Path world,
                                                         List<String> enabled) {
        int id = this.requests.incrementAndGet();
        CompletableFuture<ReloadResultPayload> result = new CompletableFuture<>();
        this.waiting.put(id, result);
        if (send == null || !send.send(new SetPacksMessage(new SetPacksPayload(id, side, world == null ? "" : world.toAbsolutePath().normalize().toString(), enabled)))) {
            this.waiting.remove(id);
            return CompletableFuture.failedFuture(new IOException("The game is not connected"));
        }
        return result.orTimeout(RELOAD_MINUTES, TimeUnit.MINUTES).whenComplete((ignored, failure) -> this.waiting.remove(id));
    }

    /** Asks the game to reload {@code kind}, merged into the next reload while one runs. */
    private synchronized CompletableFuture<ReloadResultPayload> reload(GameLocation.Connection connection, Path world,
                                                                       ReloadPayload.Kind kind, String path, boolean managed) {
        if (this.next != null && this.next.connection != connection) {
            // Asked for on an earlier connection: that game is gone, and the one connected now never saw these writes.
            IOException gone = new IOException("The game disconnected before it reloaded");
            this.next.result.completeExceptionally(gone);
            this.next.dataResult.completeExceptionally(gone);
            this.next = null;
        }
        if (this.next == null) this.next = new Batch(connection);
        Batch batch = this.next;
        if (world != null && batch.dataWorld != null && !batch.dataWorld.equals(world.toAbsolutePath().normalize())) dropData(batch);
        if (world != null) batch.dataWorld = world.toAbsolutePath().normalize();
        this.next.kinds.add(kind);
        this.next.watched.add(path);
        if (path.startsWith("assets/")) this.next.managedAssets |= managed;
        else this.next.managedData |= managed;
        CompletableFuture<ReloadResultPayload> result = world != null ? this.next.dataResult : this.next.result;
        // A write still queued joins this reload rather than taking another after it.
        if (this.running == null && this.writing == 0) sendNext();
        return result;
    }

    /** Drops the waiting reload's data when the game no longer plays its world. */
    private synchronized void dropLeftWorldData() {
        if (this.next == null || this.next.dataWorld == null || this.location.read().plays(this.next.dataWorld)) return;
        dropData(this.next);
        // A reload of that data alone leaves nothing to ask for.
        if (this.next.kinds.isEmpty()) this.next = null;
    }

    /**
     * Drops {@code batch}'s data: the game plays another world now, which never saw these writes. The batch's asset
     * reloads stay, and its data saves fail with that reason.
     */
    private static void dropData(Batch batch) {
        batch.dataResult.completeExceptionally(new IOException("The game went to another world before it reloaded"));
        batch.dataResult = new CompletableFuture<>();
        batch.dataWorld = null;
        batch.kinds.remove(ReloadPayload.Kind.DATA);
        batch.managedData = false;
    }

    private synchronized void sendNext() {
        Batch batch = this.next;
        this.next = null;
        this.running = batch;
        if (batch == null) return;
        // A full reload covers the language and textures.
        if (batch.kinds.contains(ReloadPayload.Kind.RESOURCES)) {
            batch.kinds.remove(ReloadPayload.Kind.LANGUAGE);
            batch.kinds.remove(ReloadPayload.Kind.TEXTURES);
        }
        GameLocation.Connection send = batch.connection;
        CompletableFuture<ReloadResultPayload> data = batch.dataResult;
        batch.result.whenComplete((answer, failure) -> {
            if (failure != null) data.completeExceptionally(failure);
            else data.complete(answer);
        });
        int id = this.requests.incrementAndGet();
        this.waiting.put(id, batch.result);
        List<String> watched = new ArrayList<>(batch.watched);
        if (watched.size() > ReloadPayload.MAX_WATCHED) watched = watched.subList(0, ReloadPayload.MAX_WATCHED);
        try {
            if (send == null || !send.send(new ReloadMessage(new ReloadPayload(id, batch.kinds, batch.managedAssets ? PACK_ID : "",
                    batch.managedData ? PACK_ID : "", batch.dataWorld == null ? "" : batch.dataWorld.toString(), watched)))) {
                this.waiting.remove(id);
                batch.result.completeExceptionally(new IOException("The game is not connected"));
            }
        } catch (RuntimeException unsendable) {
            // A reload that cannot be asked for fails its saves; the next reload is not held up behind it.
            this.waiting.remove(id);
            batch.result.completeExceptionally(unsendable);
        }
        batch.result.orTimeout(RELOAD_MINUTES, TimeUnit.MINUTES).whenComplete((ignored, failure) -> {
            this.waiting.remove(id);
            synchronized (this) {
                if (this.running == batch) {
                    this.running = null;
                    if (this.next != null && this.writing == 0) sendNext();
                }
            }
        });
    }

    /**
     * Enables the managed resource pack at the top of {@code options.txt} while no game runs, so the next start uses
     * it above the player's packs, as a connected game does. A game directory without {@code options.txt} has not
     * started yet; the game enables the pack on the first reload Companion asks for. Blocking.
     */
    private void enableOffline() throws IOException {
        Path options = this.workspace.resolve("options.txt");
        if (!Files.isRegularFile(options)) return;
        List<String> lines = new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8));
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.startsWith("resourcePacks:")) continue;
            JsonArray packs = JsonParser.parseString(line.substring("resourcePacks:".length())).getAsJsonArray();
            // The last listed pack is the top one.
            if (!packs.isEmpty() && packs.get(packs.size() - 1).getAsString().equals(PACK_ID)) return;
            for (int pack = packs.size() - 1; pack >= 0; pack--) {
                if (packs.get(pack).getAsString().equals(PACK_ID)) packs.remove(pack);
            }
            packs.add(PACK_ID);
            lines.set(index, "resourcePacks:" + packs);
            AtomicFiles.writeString(options, String.join("\n", lines) + "\n");
            return;
        }
        lines.add("resourcePacks:[\"vanilla\",\"" + PACK_ID + "\"]");
        AtomicFiles.writeString(options, String.join("\n", lines) + "\n");
    }

    /**
     * Creates the managed pack's {@code pack.mcmeta} when it is missing, with the pack format the running game named.
     */
    private void preparePack(Path pack, boolean assets) throws IOException {
        Path meta = pack.resolve("pack.mcmeta");
        if (Files.isRegularFile(meta)) return;
        PackStackPayload current = this.stack;
        if (current == null) {
            throw new IOException("Creating the TotalDebug pack needs the game connected, which names its pack format");
        }
        JsonObject description = new JsonObject();
        description.addProperty("pack_format", assets ? current.resourceFormat() : current.dataFormat());
        description.addProperty("description", "Changes made with TotalDebug Companion");
        JsonObject json = new JsonObject();
        json.add("pack", description);
        AtomicFiles.writeString(meta, json + "\n");
    }
}
