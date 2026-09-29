package com.github.minecraft_ta.totaldebug.client.resource;

import com.github.minecraft_ta.totaldebug.protocol.message.ClientPacksPayload;
import com.github.minecraft_ta.totaldebug.resource.PackStacks;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Tells Companion which resource packs are enabled, and which the game could enable, when an event may have changed
 * them. The world's datapacks are named by its server ({@code WorldDatapacks}). Client thread only.
 */
public final class PackStackPublisher {
    private final Path gameDirectory;
    private final Consumer<ClientPacksPayload> publish;
    private ClientPacksPayload published;

    public PackStackPublisher(Path gameDirectory, Consumer<ClientPacksPayload> publish) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory");
        this.publish = Objects.requireNonNull(publish, "publish");
    }

    /** Tells the stack unless it is what Companion was told last. */
    public void publish() {
        ClientPacksPayload current = capture();
        if (current.equals(this.published)) return;
        this.published = current;
        this.publish.accept(current);
    }

    /** Tells the stack even when unchanged, such as for a newly connected Companion. */
    public void republish() {
        this.published = null;
        publish();
    }

    private ClientPacksPayload capture() {
        return new ClientPacksPayload(PackStacks.of(Minecraft.getInstance().getResourcePackRepository(),
                SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES), this.gameDirectory.resolve("resourcepacks"), null),
                SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA));
    }
}
