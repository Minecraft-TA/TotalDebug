package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.Worlds;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetOverlayPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReloadMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.SetOverlayMessage;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.tth05.scnet.message.AbstractMessage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

/**
 * Writes edited resources into the packs Companion manages, or tries them in the running game's memory, enters them in
 * the change record and makes the game use them (see docs/RESOURCE_EDITING.md). Assets go to
 * {@code resourcepacks/TotalDebug}, data to {@code datapacks/TotalDebug} of the current world: the open one, or the one
 * played last while none is open. Files are written in the project's write queue, so the record holds every write
 * before it closes. Reloads asked for while one runs are merged into one more reload after it.
 */
public final class ResourceEdits {
    public static final String PACK_NAME = "TotalDebug";
    public static final String PACK_ID = "file/" + PACK_NAME;
    private static final long RELOAD_MINUTES = 10;

    /**
     * What a write did: when the game uses it, the pack folder it went to or null in the game's memory, and the problems
     * the game's reload logged about it, or why the reload failed in {@code reloadFailure}, empty otherwise.
     */
    public record Saved(ConfigChanges.Effect effect, Path pack, List<String> problems, String reloadFailure) {
        public Saved {
            Objects.requireNonNull(effect, "effect");
            problems = List.copyOf(problems);
            Objects.requireNonNull(reloadFailure, "reloadFailure");
        }
    }

    /** Reloads to send together. */
    private static final class Batch {
        final Set<ReloadPayload.Kind> kinds = EnumSet.noneOf(ReloadPayload.Kind.class);
        final Set<String> watched = new LinkedHashSet<>();
        final CompletableFuture<ReloadResultPayload> result = new CompletableFuture<>();
    }

    private final Path workspace;
    private final ChangeRecord record;
    private final ResourceOriginals originals;
    private final Executor writes;
    private final BooleanSupplier gameRunning;
    /** Held while a try is entered and while a disconnect clears the tries, so neither outlives the other. */
    private final Object connection = new Object();
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<Integer, CompletableFuture<ReloadResultPayload>> waiting = new ConcurrentHashMap<>();
    /** Resources tried in the game's memory, by path. */
    private final Map<String, byte[]> tried = new ConcurrentHashMap<>();
    private volatile Predicate<AbstractMessage> game;
    private volatile PackStackPayload stack;
    private Batch running;
    private Batch next;

    /**
     * {@code workspace} is the game directory, {@code writes} the project's write queue, and {@code gameRunning} tells
     * whether a game runs in the instance, connected or not.
     */
    public ResourceEdits(Path workspace, ChangeRecord record, ResourceOriginals originals, Executor writes,
                         BooleanSupplier gameRunning) {
        this.workspace = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
        this.record = Objects.requireNonNull(record, "record");
        this.originals = Objects.requireNonNull(originals, "originals");
        this.writes = Objects.requireNonNull(writes, "writes");
        this.gameRunning = Objects.requireNonNull(gameRunning, "gameRunning");
    }

    public ChangeRecord record() {
        return this.record;
    }

    public void gameConnected(Predicate<AbstractMessage> send) {
        this.game = Objects.requireNonNull(send, "send");
    }

    /** The game closed: what was only in its memory has ended, and reloads it did not answer have failed. */
    public void gameDisconnected() {
        synchronized (this.connection) {
            this.game = null;
            this.tried.clear();
            this.record.ended(ChangeRecord.Level.GAME);
        }
        this.stack = null;
        for (CompletableFuture<ReloadResultPayload> request : this.waiting.values()) {
            request.completeExceptionally(new IOException("The game disconnected before it finished reloading"));
        }
        this.waiting.clear();
    }

    /** Takes the game's enabled packs. */
    public void packStack(PackStackPayload stack) {
        this.stack = stack;
    }

    /** Takes the game's answer to a reload. */
    public void answered(ReloadResultPayload result) {
        CompletableFuture<ReloadResultPayload> request = this.waiting.remove(result.requestId());
        if (request != null) request.complete(result);
    }

    /**
     * The managed pack folder a resource is written to. Data belongs to a world: the open one, or the one played last.
     * Blocking.
     */
    public Path pack(String path) throws IOException {
        if (path.startsWith("assets/")) return this.workspace.resolve("resourcepacks").resolve(PACK_NAME);
        Path world = Worlds.open(this.workspace);
        if (world == null) world = Worlds.lastPlayed(this.workspace);
        if (world == null) throw new IOException("Data is written into a world's datapacks, and this game has no world yet");
        return world.resolve("datapacks").resolve(PACK_NAME);
    }

    /**
     * The managed pack holding {@code file}, such as a data file of another world's TotalDebug datapack opened from the
     * Changes page, or empty for a file outside the managed packs.
     */
    public Optional<Path> packOf(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        Path resources = this.workspace.resolve("resourcepacks").resolve(PACK_NAME);
        if (normalized.startsWith(resources)) return Optional.of(resources);
        Path saves = this.workspace.resolve("saves");
        if (!normalized.startsWith(saves) || saves.relativize(normalized).getNameCount() < 4) return Optional.empty();
        Path pack = saves.resolve(saves.relativize(normalized).subpath(0, 3));
        return pack.getFileName().toString().equals(PACK_NAME) && pack.getParent().getFileName().toString().equals("datapacks")
                ? Optional.of(pack) : Optional.empty();
    }

    /** The copy of a resource in {@code pack}, a managed pack, or empty when it does not hold it. Blocking. */
    public Optional<byte[]> managed(Path pack, String path) throws IOException {
        Path file = pack.resolve(path);
        return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
    }

    /** The content tried in the game's memory for a resource, or empty. */
    public Optional<byte[]> tried(String path) {
        return Optional.ofNullable(this.tried.get(path)).map(byte[]::clone);
    }

    /**
     * Puts {@code content} into the game's in-memory pack and makes the game use it, without writing a file. Data needs
     * an open world.
     */
    public CompletableFuture<Saved> tryInGame(String path, byte[] content) {
        Objects.requireNonNull(content, "content");
        Predicate<AbstractMessage> send = this.game;
        if (send == null) return CompletableFuture.failedFuture(new IOException("Trying a resource in the game needs the game running"));
        if (content.length > SetOverlayPayload.MAX_CONTENT_BYTES) {
            return CompletableFuture.failedFuture(new IOException("The game takes at most " + SetOverlayPayload.MAX_CONTENT_BYTES
                    + " bytes per resource; this one has " + content.length));
        }
        return write(() -> {
            if (!path.startsWith("assets/") && Worlds.open(this.workspace) == null) {
                throw new CompletionException(new IOException("Data is tried in an open world, and none is open"));
            }
            synchronized (this.connection) {
                // A game that disconnected meanwhile has dropped its tries; this one must not outlive them.
                if (this.game != send || !send.test(new SetOverlayMessage(new SetOverlayPayload(path, content)))) {
                    throw new CompletionException(new IOException("The game is not connected"));
                }
                byte[] previous = this.tried.put(path, content.clone());
                this.record.changed(new ChangeRecord.Resource(path, null), ChangeRecord.Level.GAME,
                        ResourceOriginals.hash(previous), ResourceOriginals.hash(content));
            }
            return path;
        }).thenCompose(ignored -> applyInGame(path)).thenApply(saved -> {
            // A disconnect at any point ends the try, and the caller must not show it as still in the game.
            if (!this.tried.containsKey(path)) throw new CompletionException(new IOException("The try ended before the game reloaded it"));
            return saved;
        });
    }

    /** Removes a resource from the game's in-memory pack; returns whether it held one. */
    private boolean untry(String path) {
        if (this.tried.remove(path) == null) return false;
        Predicate<AbstractMessage> send = this.game;
        if (send != null) send.test(new SetOverlayMessage(new SetOverlayPayload(path, null)));
        this.record.dropped(new ChangeRecord.Resource(path, null), ChangeRecord.Level.GAME);
        return true;
    }

    /**
     * The title of an enabled pack above the managed pack that also supplies {@code path}, whose copy the game uses
     * instead; empty when none does or the game has not named its packs. Blocking.
     */
    public Optional<String> overriddenBy(String path) {
        PackStackPayload current = this.stack;
        if (current == null) return Optional.empty();
        List<PackStackPayload.Pack> packs = path.startsWith("assets/") ? current.resourcePacks() : current.dataPacks();
        int managed = -1;
        for (int index = 0; index < packs.size(); index++) {
            if (packs.get(index).id().equals(PACK_ID)) managed = index;
        }
        if (managed < 0) return Optional.empty();
        for (int index = packs.size() - 1; index > managed; index--) {
            PackStackPayload.Pack pack = packs.get(index);
            if (!pack.source().isEmpty() && contains(Path.of(pack.source()), path)) return Optional.of(pack.title());
        }
        return Optional.empty();
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
     * Writes {@code content} into {@code into}, a managed pack, or into the current world's when it is null, and makes
     * the game use it.
     */
    public CompletableFuture<Saved> save(String path, Path into, byte[] content) {
        Objects.requireNonNull(content, "content");
        return write(() -> {
            try {
                Path pack = into != null ? into : pack(path);
                preparePack(pack, path.startsWith("assets/"));
                Path file = pack.resolve(path);
                byte[] previous = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
                ChangeRecord.Resource target = new ChangeRecord.Resource(path, pack);
                if (this.record.change(target, ChangeRecord.Level.PACK) == null) this.originals.keep(previous);
                AtomicFiles.replace(file, staged -> Files.write(staged, content));
                this.record.changed(target, ChangeRecord.Level.PACK, ResourceOriginals.hash(previous), ResourceOriginals.hash(content));
                // The saved file is what the game should show, not an earlier try above it.
                untry(path);
                return pack;
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }).thenCompose(pack -> apply(path, pack));
    }

    /**
     * Puts back what the managed pack held before Companion first changed {@code change}, or removes a resource tried
     * in the game, and makes the game use it.
     */
    public CompletableFuture<Saved> revert(ChangeRecord.Change change) {
        if (change.target() instanceof ChangeRecord.Resource target && change.level() == ChangeRecord.Level.GAME) {
            return write(() -> {
                untry(target.path());
                return target.path();
            }).thenCompose(this::applyInGame);
        }
        if (!(change.target() instanceof ChangeRecord.Resource target) || change.level() != ChangeRecord.Level.PACK) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Not a resource in the managed pack"));
        }
        return write(() -> {
            try {
                byte[] original = this.originals.read(change.original());
                Path file = target.location().resolve(target.path());
                if (original == null) Files.deleteIfExists(file);
                else AtomicFiles.replace(file, staged -> Files.write(staged, original));
                this.record.changed(target, ChangeRecord.Level.PACK, change.current(), change.original());
                return target.location();
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }).thenCompose(pack -> apply(target.path(), pack));
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
     * Whether the managed pack still holds what Companion wrote for {@code change}; a change whose original is back
     * leaves the record. Blocking.
     */
    public boolean holds(ChangeRecord.Change change) {
        if (!(change.target() instanceof ChangeRecord.Resource target) || target.location() == null) return true;
        Path file = target.location().resolve(target.path());
        String hash;
        try {
            hash = ResourceOriginals.hash(Files.isRegularFile(file) ? Files.readAllBytes(file) : null);
        } catch (IOException unreadable) {
            return true;
        }
        this.record.observed(target, change.level(), hash, String::equals);
        return hash.equals(change.current());
    }

    /** Reloads what the running game needs to use a resource changed in its memory. */
    private CompletableFuture<Saved> applyInGame(String path) {
        if (this.game == null) {
            return CompletableFuture.completedFuture(new Saved(ConfigChanges.Effect.GAME_STARTS, null, List.of(), ""));
        }
        if (ResourcePaths.apply(path) == ResourcePaths.Apply.WORLD_LOAD) {
            return CompletableFuture.completedFuture(new Saved(ConfigChanges.Effect.REJOIN, null, List.of(), ""));
        }
        return reload(kind(path), path).handle((result, failure) -> {
            if (failure != null) return new Saved(ConfigChanges.Effect.RESTART, null, List.of(), message(failure));
            return new Saved(result.error().isEmpty() ? ConfigChanges.Effect.NOW : ConfigChanges.Effect.RESTART, null,
                    result.problems(), result.error());
        });
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
            if (assets) {
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
        return reload(kind(path), path).handle((result, failure) -> {
            if (failure != null) {
                return new Saved(assets ? ConfigChanges.Effect.GAME_STARTS : ConfigChanges.Effect.WORLD_OPENS, pack,
                        List.of(), message(failure));
            }
            return new Saved(result.error().isEmpty() ? ConfigChanges.Effect.NOW : assets ? ConfigChanges.Effect.GAME_STARTS
                    : ConfigChanges.Effect.WORLD_OPENS, pack, result.problems(), result.error());
        });
    }

    /** Asks the game to reload {@code kind}, merged into the next reload while one runs. */
    private synchronized CompletableFuture<ReloadResultPayload> reload(ReloadPayload.Kind kind, String path) {
        if (this.next == null) this.next = new Batch();
        this.next.kinds.add(kind);
        this.next.watched.add(path);
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
        if (send == null || !send.test(new ReloadMessage(new ReloadPayload(id, batch.kinds, PACK_ID, watched)))) {
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
     * it. A game directory without {@code options.txt} has not started yet; the game enables the pack on the first
     * reload Companion asks for. Blocking.
     */
    private void enableOffline() throws IOException {
        Path options = this.workspace.resolve("options.txt");
        if (!Files.isRegularFile(options)) return;
        List<String> lines = new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8));
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.startsWith("resourcePacks:")) continue;
            JsonArray packs = JsonParser.parseString(line.substring("resourcePacks:".length())).getAsJsonArray();
            for (JsonElement pack : packs) {
                if (pack.getAsString().equals(PACK_ID)) return;
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
