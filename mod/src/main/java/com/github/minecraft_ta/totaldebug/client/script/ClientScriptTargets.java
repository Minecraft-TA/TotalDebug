package com.github.minecraft_ta.totaldebug.client.script;

import com.github.minecraft_ta.totaldebug.client.inspection.KeptStacks;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import com.github.minecraft_ta.totaldebug.script.ScriptTargetResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

import java.util.Objects;

/** Resolves script targets in the client's copy of the current level, and the stacks the client kept. */
final class ClientScriptTargets implements ScriptTargetResolver {
    private final KeptStacks stacks;

    ClientScriptTargets(KeptStacks stacks) {
        this.stacks = Objects.requireNonNull(stacks, "stacks");
    }

    @Override
    public ScriptTarget resolve(SubjectRef.Occurrence subject) {
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
            case SubjectRef.Stack stack -> new ScriptTarget.SelectedStack(this.stacks.get(stack.selection())
                    .orElseThrow(() -> new IllegalStateException("The game no longer keeps this stack; select it again with F6")),
                    stack, level);
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
