package com.github.minecraft_ta.totaldebug.client;

import com.github.minecraft_ta.totaldebug.client.catalog.KeyBindingEdits;
import com.github.minecraft_ta.totaldebug.client.catalog.PackCatalogCapture;
import com.github.minecraft_ta.totaldebug.client.catalog.PackCatalogPublisher;
import com.github.minecraft_ta.totaldebug.client.companion.ChangePublisher;
import com.github.minecraft_ta.totaldebug.client.companion.ClientRelay;
import com.github.minecraft_ta.totaldebug.client.companion.CompanionAppClient;
import com.github.minecraft_ta.totaldebug.client.companion.CompanionRequests;
import com.github.minecraft_ta.totaldebug.client.companion.CompanionProgressActionBar;
import com.github.minecraft_ta.totaldebug.client.input.CodeViewInput;
import com.github.minecraft_ta.totaldebug.client.inspection.ItemIcons;
import com.github.minecraft_ta.totaldebug.client.inspection.ResourceSnapshots;
import com.github.minecraft_ta.totaldebug.client.input.Selection;
import com.github.minecraft_ta.totaldebug.client.inspection.KeptStacks;
import com.github.minecraft_ta.totaldebug.client.resource.PackStackPublisher;
import com.github.minecraft_ta.totaldebug.client.world.Playing;
import com.github.minecraft_ta.totaldebug.client.resource.ResourceReloads;
import com.github.minecraft_ta.totaldebug.client.script.ClientScriptService;
import com.github.minecraft_ta.totaldebug.config.TotalDebugConfig;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.KeyBindingResultMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.PackStackMessage;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.PlayingMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ReloadResultMessage;
import com.github.minecraft_ta.totaldebug.storage.GameLock;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import java.io.IOException;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the client-only runtime assembled after Minecraft has reached client setup. */
public final class TotalDebugClient {
    private static volatile TotalDebugClient instance;
    /** Held, never read: the lock lasts as long as the game. */
    private static GameLock gameLock;

    private final CompanionAppClient companionApp;
    private final CompanionRequests requests;
    private final CodeViewOperation codeView;
    private final CodeViewInput codeViewInput;
    private final KeptStacks keptStacks = new KeptStacks();
    private final ClientScriptService scripts;
    private final ClientRelay relay;
    private final ResourceSnapshots resources;
    private final PackCatalogPublisher catalogs;
    private final PackStackPublisher packStacks;
    private final ChangePublisher<PlayingPayload> playing;
    private final AtomicReference<PackCatalogCapture> catalogCapture = new AtomicReference<>();
    private volatile boolean snapshotRequested;
    private volatile String gameSessionId;

    private TotalDebugClient(Path gameDirectory) {
        Path totalDebugDirectory = gameDirectory
                .resolve("total-debug")
                .toAbsolutePath()
                .normalize();
        InstancePaths paths = new InstancePaths(totalDebugDirectory);
        holdGameLock(paths);
        CompanionAppClient companionApp = new CompanionAppClient(
                totalDebugDirectory,
                TotalDebugConfig.CLIENT.companionDevelopmentJar.get()
        );
        this.companionApp = companionApp;
        companionApp.setProgressListener(progress -> CompanionProgressActionBar.show(Minecraft.getInstance(), progress));
        this.requests = new CompanionRequests(companionApp);
        this.resources = new ResourceSnapshots(paths.previews(), companionApp::sendResourceSnapshot);
        this.catalogs = new PackCatalogPublisher(
                paths.catalog(),
                () -> Minecraft.getInstance().getLanguageManager().getSelected(),
                (inventoryId, language, modules) -> {
                    PackCatalogCapture capture = new PackCatalogCapture(inventoryId, language, modules);
                    PackCatalogCapture previous = this.catalogCapture.getAndSet(capture);
                    if (previous != null) {
                        previous.result().cancel(false);
                    }
                    return capture.result();
                },
                companionApp::sendPackCatalog
        );
        this.packStacks = new PackStackPublisher(gameDirectory, stack -> companionApp.sendPackStack(new PackStackMessage(stack)));
        this.playing = new ChangePublisher<>(Playing::capture, playing -> {
            companionApp.sendPlaying(new PlayingMessage(playing));
            // Companion drops the packs of what the game played before; they are named again for what it plays now.
            this.packStacks.republish();
        });
        companionApp.setSessionOpenedHandler(() -> {
            this.packStacks.republish();
            this.playing.republish();
        });
        companionApp.setPackCatalogHandler((inventoryId, modules) -> {
            // Icons are drawn from the resource snapshot, which must follow the current packs even when the
            // saved catalog is reused.
            this.snapshotRequested = true;
            this.catalogs.request(inventoryId, modules);
        });
        companionApp.setKeyBindingHandler(message -> Minecraft.getInstance().execute(() ->
                companionApp.sendKeyBindingResult(new KeyBindingResultMessage(KeyBindingEdits.apply(message.payload())))));
        companionApp.setReloadHandler(message -> Minecraft.getInstance().execute(() -> ResourceReloads.reload(message.payload(),
                result -> companionApp.sendReloadResult(new ReloadResultMessage(result)))));
        companionApp.setPacksHandler(message -> Minecraft.getInstance().execute(() -> ResourceReloads.select(message.payload(),
                result -> companionApp.sendReloadResult(new ReloadResultMessage(result)))));
        this.codeView = new CodeViewOperation(new CodeViewOperation.Actions() {
            @Override
            public void inspect(Selection subject) {
                TotalDebugClient.this.requests.inspect(new InspectSubjectPayload(
                        gameSession(),
                        subject.subject().format(),
                        subject.identity(),
                        subject.icon().map(ItemIcons.Icon::model).orElse(""),
                        subject.icon().map(ItemIcons.Icon::tints).orElse(Map.of())
                ));
                // Icons are only drawn in Companion; with it turned off, the capture would never be read.
                if (TotalDebugConfig.CLIENT.useCompanionApp.get()) TotalDebugClient.this.resources.prepare();
            }

            @Override
            public void focusCompanion() {
                TotalDebugClient.this.requests.focusCompanion();
            }
        });
        this.codeViewInput = new CodeViewInput(this.codeView::inspectOrFocus, this.keptStacks);
        this.scripts = new ClientScriptService(companionApp, TotalDebug.get().tickTasks(), () -> this.gameSessionId,
                this.keptStacks);
        ClientRelay relay = new ClientRelay(companionApp, () -> this.gameSessionId);
        this.relay = relay;
        companionApp.setToServerHandler(message -> Minecraft.getInstance().execute(() -> relay.toServer(message)));
        TotalDebug.get().network().setCompanionReceiver(relay::fromServer);
        companionApp.setScriptRequestHandler(this.scripts::handleRunRequest);
        companionApp.setStopScriptHandler(this.scripts::stopScript);
        companionApp.setSessionClosedHandler(this.scripts::close);
        companionApp.startDiscovery(() -> TotalDebugConfig.CLIENT.useCompanionApp.get());
    }

    /**
     * Holds the instance's game lock until the game exits, so Companion does not write the game's files while it runs
     * without a connection. The process ending releases it.
     */
    private static void holdGameLock(InstancePaths paths) {
        try {
            gameLock = GameLock.hold(paths.gameLock());
        } catch (IOException exception) {
            TotalDebug.LOGGER.warn("Companion cannot tell that this game is running: {}", exception.getMessage());
        }
    }

    public static synchronized void initialize(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        if (instance != null) {
            throw new IllegalStateException("TotalDebug client was initialized more than once");
        }
        instance = new TotalDebugClient(minecraft.gameDirectory.toPath());
    }

    public static TotalDebugClient get() {
        TotalDebugClient current = instance;
        if (current == null) {
            throw new IllegalStateException("TotalDebug client has not been initialized yet");
        }
        return current;
    }

    public static Optional<TotalDebugClient> current() {
        return Optional.ofNullable(instance);
    }

    /**
     * A client resource reload finished, such as after a language or resource pack change. The catalog holds
     * translated names and the snapshot the winning resources, so both are brought up to date.
     */
    public void resourcesReloaded() {
        this.catalogs.recapture();
        this.companionApp.announceInventory();
    }


    public CodeViewInput codeViewInput() {
        return this.codeViewInput;
    }

    /**
     * Advances a pending pack catalog capture once resources have finished loading, then publishes the resource
     * snapshot Companion draws its icons from. Client thread only.
     */
    public void onClientTick() {
        // What the game plays is told first, and during a loading overlay too, since Companion reads the packs by it.
        this.playing.tick();
        if (Minecraft.getInstance().getOverlay() != null) {
            return;
        }
        this.packStacks.tick();
        ResourceReloads.tick();
        PackCatalogCapture capture = this.catalogCapture.get();
        if (capture != null) {
            if (!capture.step() || !this.catalogCapture.compareAndSet(capture, null)) {
                return;
            }
            this.snapshotRequested = true;
        }
        if (this.snapshotRequested) {
            this.snapshotRequested = false;
            this.resources.prepare();
        }
    }

    public void onServerDisconnect() {
        synchronized (this) {
            this.gameSessionId = null;
        }
        this.relay.serverLeft();
        this.scripts.onServerDisconnect();
    }

    /** Identifies the world joined since the last logout, so a run against a left world is rejected. */
    private synchronized String gameSession() {
        if (this.gameSessionId == null) {
            this.gameSessionId = UUID.randomUUID().toString();
        }
        return this.gameSessionId;
    }
}
