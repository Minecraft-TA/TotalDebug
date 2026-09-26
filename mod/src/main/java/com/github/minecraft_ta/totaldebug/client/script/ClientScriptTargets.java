package com.github.minecraft_ta.totaldebug.client.script;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import com.github.minecraft_ta.totaldebug.script.ScriptTargetResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

/** Resolves script targets in the client's copy of the current level. */
final class ClientScriptTargets implements ScriptTargetResolver {
    @Override
    public ScriptTarget resolve(SubjectRef subject) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            throw new IllegalStateException("The client is not in a world");
        }
        return switch (subject) {
            case SubjectRef.Block block -> {
                String current = level.dimension().location().toString();
                if (!current.equals(block.dimension())) {
                    throw new IllegalStateException("The client is in " + current + ", not " + block.dimension());
                }
                yield ScriptTargetResolver.block(level, block);
            }
            case SubjectRef.Entity entity -> entity(level, entity);
        };
    }

    private static ScriptTarget.LiveEntity entity(ClientLevel level, SubjectRef.Entity subject) {
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.getUUID().equals(subject.uuid())) {
                return new ScriptTarget.LiveEntity(entity, subject, level);
            }
        }
        throw new IllegalStateException("The client has no loaded entity with UUID " + subject.uuid());
    }
}
