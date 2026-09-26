package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionText;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResultCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

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
    private final ScriptFacts facts = new ScriptFacts(this.output::append);
    private Supplier<ScriptTarget> targetSource;
    private ScriptTarget target;

    /** Structured sections reported with this run's result, shown by Companion alongside the returned value. */
    public final ScriptFacts facts() {
        return this.facts;
    }

    /**
     * The block or entity this run was started for, found in this side's world on first use.
     *
     * @throws IllegalStateException when the run has no target or the target is not loaded
     */
    public final ScriptTarget target() {
        if (this.target == null) {
            if (this.targetSource == null) {
                throw new IllegalStateException("This run has no target; run it from an inspection");
            }
            this.target = this.targetSource.get();
        }
        return this.target;
    }

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

    final void bindTarget(Supplier<ScriptTarget> source) {
        this.targetSource = source;
    }

    final ExecutionText output() {
        return this.output.snapshot();
    }
}
