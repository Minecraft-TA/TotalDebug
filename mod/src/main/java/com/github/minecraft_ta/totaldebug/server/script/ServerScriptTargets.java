package com.github.minecraft_ta.totaldebug.server.script;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import com.github.minecraft_ta.totaldebug.script.ScriptTargetResolver;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import java.util.Objects;

/** Resolves script targets in the logical server's levels. */
final class ServerScriptTargets implements ScriptTargetResolver {
    private final MinecraftServer server;

    ServerScriptTargets(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public ScriptTarget resolve(SubjectRef subject) {
        return switch (subject) {
            case SubjectRef.Block block -> ScriptTargetResolver.block(level(block.dimension()), block);
            case SubjectRef.Entity entity -> entity(entity);
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
