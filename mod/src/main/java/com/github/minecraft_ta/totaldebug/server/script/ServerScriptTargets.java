package com.github.minecraft_ta.totaldebug.server.script;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.script.HeldStacks;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import com.github.minecraft_ta.totaldebug.script.ScriptTargetResolver;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.Objects;

/** Resolves script targets in the logical server's levels and its players' inventories. */
final class ServerScriptTargets implements ScriptTargetResolver {
    private final MinecraftServer server;
    private final HeldStacks stacks = new HeldStacks();

    ServerScriptTargets(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public ScriptTarget resolve(SubjectRef.Occurrence subject) {
        return switch (subject) {
            case SubjectRef.Block block -> ScriptTargetResolver.block(level(block.dimension()), block);
            case SubjectRef.Entity entity -> entity(entity);
            case SubjectRef.Stack stack -> {
                ServerPlayer player = this.server.getPlayerList().getPlayer(stack.player());
                if (player == null) {
                    throw new IllegalStateException("The player holding the stack is not on the server");
                }
                yield this.stacks.resolve(player, stack);
            }
        };
    }

    private ServerLevel level(String dimension) {
        ServerLevel level = this.server.getLevel(
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
        if (level == null) {
            throw new IllegalStateException("The server has no loaded dimension " + dimension);
        }
        return level;
    }

    private ScriptTarget.LiveEntity entity(SubjectRef.Entity subject) {
        for (ServerLevel level : this.server.getAllLevels()) {
            Entity entity = level.getEntity(subject.uuid());
            if (entity != null) {
                return new ScriptTarget.LiveEntity(entity, subject, level);
            }
        }
        throw new IllegalStateException("No loaded entity on the server has UUID " + subject.uuid());
    }
}
