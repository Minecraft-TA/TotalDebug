package com.github.minecraft_ta.totaldebug.script;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runtime API inherited by generated live-script classes.
 *
 * <p>The Companion presents scripts as Java snippets. Their generated class and this
 * entry point are implementation details, while these convenience methods form the
 * deliberately small execution context available to a snippet.</p>
 */
public abstract class ScriptProgram {
    private static final Object NO_RESULT = new Object();

    private final ExecutionTextBuffer output = new ExecutionTextBuffer(ExecutionResultCodec.MAX_WIRE_BYTES);

    public final MinecraftServer getServer() {
        return Objects.requireNonNull(
                ServerLifecycleHooks.getCurrentServer(),
                "No Minecraft server is running in this JVM"
        );
    }

    public final void sendToAllPlayers(String message) {
        getServerPlayers().forEach(player -> player.sendSystemMessage(Component.literal(message)));
    }

    public final ServerLevel getServerOverworld() {
        return getServer().overworld();
    }

    public final List<ServerLevel> getServerWorlds() {
        List<ServerLevel> levels = new ArrayList<>();
        getServer().getAllLevels().forEach(levels::add);
        return List.copyOf(levels);
    }

    public final List<ServerPlayer> getServerPlayers() {
        return getServer().getPlayerList().getPlayers();
    }

    public final void logln(Object value) {
        log(value);
        log(System.lineSeparator());
    }

    public final void log(Object value) {
        this.output.append(value);
    }

    /** Executes the generated snippet body. */
    public abstract Object run() throws Throwable;

    /** Used only by the generated wrapper when a statement snippet falls through. */
    protected final Object noResult() {
        return NO_RESULT;
    }

    final boolean isNoResult(Object value) {
        return value == NO_RESULT;
    }

    final ExecutionText output() {
        return this.output.snapshot();
    }
}
