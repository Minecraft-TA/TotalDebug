package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CurrentWorld;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackFolders;
import com.github.minecraft_ta.totalDebugCompanion.catalog.Worlds;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReloadMessage;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.tth05.scnet.message.AbstractMessage;
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
import java.util.List;
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
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

/**
 * Writes edited resources into packs, enters them in the change record and makes the game use them (see
 * docs/RESOURCE_EDITING.md). A file opened from a folder pack is saved in that pack. Any other file is saved into the
 * working pack of its side: the pack Companion manages unless another folder pack was chosen, which for assets is
 * {@code resourcepacks/TotalDebug} and for data {@code datapacks/TotalDebug} of the current world, the open one or the
 * one played last while none is open. Only the managed pack is enabled and placed on top; the player's own packs keep
 * their place. Files are written in the project's write queue, so the record holds every write before it closes.
 * Reloads asked for while one runs are merged into one more reload after it.
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

    /** Reloads to send together. */
    private static final class Batch {
        final Set<ReloadPayload.Kind> kinds = EnumSet.noneOf(ReloadPayload.Kind.class);
        final Set<String> watched = new LinkedHashSet<>();
        final CompletableFuture<ReloadResultPayload> result = new CompletableFuture<>();
        /** Whether a save in the batch went into the managed pack, which the game then enables on top. */
        boolean managed;
    }

    private final Path workspace;
    private final ChangeRecord record;
    private final ResourceOriginals originals;
    private final Executor writes;
    private final BooleanSupplier gameRunning;
    private final InstanceState state;
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<Integer, CompletableFuture<ReloadResultPayload>> waiting = new ConcurrentHashMap<>();
    private volatile Predicate<AbstractMessage> game;
    private volatile PackStackPayload stack;
    /** Run whenever the game names its packs again, such as after another world opened. */
    private final List<Runnable> stackListeners = new CopyOnWriteArrayList<>();
    /** Run after a save or revert has written its file and the game used it, or failed to. */
    private final List<Runnable> editListeners = new CopyOnWriteArrayList<>();
    /** Run when a working pack is chosen, which changes where resource tabs save. */
    private final List<Runnable> workingPackListeners = new CopyOnWriteArrayList<>();
    private Batch running;
    private Batch next;

    /**
     * {@code workspace} is the game directory, {@code writes} the project's write queue, {@code gameRunning} tells
     * whether a game runs in the instance, connected or not, and {@code state} keeps the working pack of each side.
     */
    public ResourceEdits(Path workspace, ChangeRecord record, ResourceOriginals originals, Executor writes,
                         BooleanSupplier gameRunning, InstanceState state) {
        this.workspace = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
        this.record = Objects.requireNonNull(record, "record");
        this.originals = Objects.requireNonNull(originals, "originals");
        this.writes = Objects.requireNonNull(writes, "writes");
        this.gameRunning = Objects.requireNonNull(gameRunning, "gameRunning");
        this.state = Objects.requireNonNull(state, "state");
    }

    public ChangeRecord record() {
        return this.record;
    }

    public void gameConnected(Predicate<AbstractMessage> send) {
        this.game = Objects.requireNonNull(send, "send");
    }

    /** The game closed: reloads it did not answer have failed. */
    public void gameDisconnected() {
        this.game = null;
        this.stack = null;
        this.stackListeners.forEach(Runnable::run);
        for (CompletableFuture<ReloadResultPayload> request : this.waiting.values()) {
            request.completeExceptionally(new IOException("The game disconnected before it finished reloading"));
        }
        this.waiting.clear();
    }

    /** Takes the game's enabled packs. */
    public void packStack(PackStackPayload stack) {
        this.stack = stack;
        this.stackListeners.forEach(Runnable::run);
    }

    /** The packs the running game named last, or null while no game is connected. */
    public PackStackPayload packStack() {
        return this.stack;
    }

    /**
     * Runs {@code listener} whenever the game names its packs again, or disconnects, on the thread that saw it; returns
     * its removal.
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
        this.workingPackListeners.forEach(Runnable::run);
    }

    /** Runs {@code listener} whenever a working pack is chosen; returns what removes it. */
    public Runnable addWorkingPackListener(Runnable listener) {
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
        Path world = Worlds.open(this.workspace);
        if (world == null) world = Worlds.lastPlayed(this.workspace);
        if (world == null) throw new IOException("Data is written into a world's datapacks, and this game has no world yet");
        return world.resolve("datapacks");
    }

    private static String side(String path) {
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
        return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
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
        if (current == null || !assets && !Worlds.isOpen(pack.getParent().getParent())) return disabledOnDisk(path, pack);
        List<PackStackPayload.Pack> packs = assets ? current.resourcePacks() : current.dataPacks();
        String id = "file/" + pack.getFileName();
        int position = -1;
        for (int index = 0; index < packs.size(); index++) {
            if (packs.get(index).id().equals(id)) position = index;
        }
        if (position < 0) {
            // The managed pack is enabled by the reload after a save.
            return managed(pack) ? Optional.empty() : Optional.of(notEnabled(pack));
        }
        for (int index = packs.size() - 1; index > position; index--) {
            PackStackPayload.Pack above = packs.get(index);
            if (!above.source().isEmpty() && contains(Path.of(above.source()), path)) {
                return Optional.of(above.title() + " is above the " + PackFolders.label(pack) + " and supplies this file too, so the game shows its copy");
            }
        }
        return Optional.empty();
    }

    /**
     * Why the game will not use {@code pack}'s copy when it next starts or loads the world: the pack not being enabled in
     * {@code options.txt}, or disabled in the world's {@code level.dat}. The managed pack is enabled when saved to.
     */
    private Optional<String> disabledOnDisk(String path, Path pack) {
        if (managed(pack)) return Optional.empty();
        String id = "file/" + pack.getFileName();
        try {
            if (path.startsWith("assets/")) {
                return PackResources.enabledInOptions(this.workspace.resolve("options.txt")).contains(id)
                        ? Optional.empty() : Optional.of(notEnabled(pack));
            }
            boolean disabled = CurrentWorld.read(pack.getParent().getParent()).datapacks().stream()
                    .anyMatch(listed -> listed.id().equals(id) && listed.state() == ListedPack.State.DISABLED);
            return disabled ? Optional.of(notEnabled(pack)) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static String notEnabled(Path pack) {
        return "The " + PackFolders.label(pack) + " is not enabled, so the game does not use this file";
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
        Objects.requireNonNull(content, "content");
        return finished(write(() -> {
            try {
                Path pack = into != null ? into : pack(path);
                if (managed(pack)) preparePack(pack, path.startsWith("assets/"));
                Path file = pack.resolve(path);
                byte[] previous = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
                ChangeRecord.Resource target = new ChangeRecord.Resource(path, pack);
                if (this.record.change(target) == null) this.originals.keep(previous);
                AtomicFiles.replace(file, staged -> Files.write(staged, content));
                this.record.changed(target, ResourceOriginals.hash(previous), ResourceOriginals.hash(content));
                return pack;
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }).thenCompose(pack -> apply(path, pack))
                // A pack of the player's that is not enabled or lies below another keeps its copy unused, reloaded or not.
                .thenApply(saved -> new Saved(saved.effect(), saved.pack(), saved.problems(), saved.reloadFailure(),
                        unusedBecause(path, saved.pack()).orElse(""))));
    }

    /**
     * Puts back what the managed pack held before Companion first changed {@code change}, and makes the game use it. A
     * file changed outside Companion since is left alone, unless it holds the original again.
     */
    public CompletableFuture<Saved> revert(ChangeRecord.Change change) {
        if (!(change.target() instanceof ChangeRecord.Resource target)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Not a resource in the managed pack"));
        }
        return finished(write(() -> {
            try {
                // Reverted already, such as twice from the Changes page before it refreshed.
                if (!change.equals(this.record.change(target))) return target.location();
                Path file = target.location().resolve(target.path());
                String held = ResourceOriginals.hash(Files.isRegularFile(file) ? Files.readAllBytes(file) : null);
                if (!held.equals(change.current()) && !held.equals(change.original())) {
                    throw new IOException(target.path() + " was changed outside Companion since, and reverting would replace that");
                }
                if (!held.equals(change.original())) {
                    byte[] original = this.originals.read(change.original());
                    if (original == null) Files.deleteIfExists(file);
                    else AtomicFiles.replace(file, staged -> Files.write(staged, original));
                }
                this.record.changed(target, change.current(), change.original());
                return target.location();
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }).thenCompose(pack -> apply(target.path(), pack)));
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
        } catch (IOException unreadable) {
            // An unreadable file keeps its change.
        }
    }

    /** The problems a reload logged about {@code path}; saves merged into one reload each see only their own. */
    private static List<String> problemsOf(ReloadResultPayload result, String path) {
        return result.problems().stream().filter(problem -> problem.path().equals(path))
                .map(ReloadResultPayload.Problem::message).toList();
    }

    private static ReloadPayload.Kind kind(String path) {
        return switch (ResourcePaths.apply(path)) {
            case LANGUAGE -> ReloadPayload.Kind.LANGUAGE;
            case RESOURCES -> ReloadPayload.Kind.RESOURCES;
            default -> ReloadPayload.Kind.DATA;
        };
    }

    private static String message(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /** Tells when the game uses a resource written to {@code pack}, reloading what it needs when it runs. */
    private CompletableFuture<Saved> apply(String path, Path pack) {
        ResourcePaths.Apply apply = ResourcePaths.apply(path);
        boolean assets = path.startsWith("assets/");
        if (this.game == null) {
            if (this.gameRunning.getAsBoolean()) {
                // A running game writes options.txt itself, and only a connected one can reload.
                return CompletableFuture.completedFuture(new Saved(assets ? ConfigChanges.Effect.GAME_STARTS
                        : ConfigChanges.Effect.WORLD_OPENS, pack, List.of(),
                        "The game is running but not connected to Companion; connect it to use the change"));
            }
            if (assets && managed(pack)) {
                try {
                    enableOffline();
                } catch (IOException | RuntimeException exception) {
                    // The file is saved either way; a malformed options.txt only keeps the pack from being enabled.
                    return CompletableFuture.completedFuture(new Saved(ConfigChanges.Effect.GAME_STARTS, pack, List.of(),
                            "The TotalDebug pack could not be enabled in options.txt: " + exception.getMessage()));
                }
            }
            return CompletableFuture.completedFuture(new Saved(assets ? ConfigChanges.Effect.GAME_STARTS
                    : ConfigChanges.Effect.WORLD_OPENS, pack, List.of(), ""));
        }
        if (!assets) {
            // A world's datapack is read by that world only.
            if (!Worlds.isOpen(pack.getParent().getParent())) {
                return CompletableFuture.completedFuture(new Saved(ConfigChanges.Effect.WORLD_OPENS, pack, List.of(), ""));
            }
            if (apply == ResourcePaths.Apply.WORLD_LOAD) {
                return CompletableFuture.completedFuture(new Saved(ConfigChanges.Effect.REJOIN, pack, List.of(), ""));
            }
        }
        return reload(kind(path), path, managed(pack)).handle((result, failure) -> {
            if (failure != null) {
                return new Saved(assets ? ConfigChanges.Effect.GAME_STARTS : ConfigChanges.Effect.WORLD_OPENS, pack,
                        List.of(), message(failure));
            }
            return new Saved(result.error().isEmpty() ? ConfigChanges.Effect.NOW : assets ? ConfigChanges.Effect.GAME_STARTS
                    : ConfigChanges.Effect.WORLD_OPENS, pack, problemsOf(result, path), result.error());
        });
    }

    /** Asks the game to reload {@code kind}, merged into the next reload while one runs. */
    private synchronized CompletableFuture<ReloadResultPayload> reload(ReloadPayload.Kind kind, String path, boolean managed) {
        if (this.next == null) this.next = new Batch();
        this.next.kinds.add(kind);
        this.next.watched.add(path);
        this.next.managed |= managed;
        CompletableFuture<ReloadResultPayload> result = this.next.result;
        if (this.running == null) sendNext();
        return result;
    }

    private synchronized void sendNext() {
        Batch batch = this.next;
        this.next = null;
        this.running = batch;
        if (batch == null) return;
        if (batch.kinds.contains(ReloadPayload.Kind.RESOURCES)) batch.kinds.remove(ReloadPayload.Kind.LANGUAGE);
        Predicate<AbstractMessage> send = this.game;
        int id = this.requests.incrementAndGet();
        this.waiting.put(id, batch.result);
        List<String> watched = new ArrayList<>(batch.watched);
        if (watched.size() > ReloadPayload.MAX_WATCHED) watched = watched.subList(0, ReloadPayload.MAX_WATCHED);
        if (send == null || !send.test(new ReloadMessage(new ReloadPayload(id, batch.kinds, batch.managed ? PACK_ID : "", watched)))) {
            this.waiting.remove(id);
            batch.result.completeExceptionally(new IOException("The game is not connected"));
        }
        batch.result.orTimeout(RELOAD_MINUTES, TimeUnit.MINUTES).whenComplete((ignored, failure) -> {
            this.waiting.remove(id);
            synchronized (this) {
                if (this.running == batch) {
                    this.running = null;
                    if (this.next != null) sendNext();
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
