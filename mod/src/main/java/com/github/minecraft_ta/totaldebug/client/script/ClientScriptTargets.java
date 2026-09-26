package com.github.minecraft_ta.totaldebug.client.script;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.script.HeldStacks;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import com.github.minecraft_ta.totaldebug.script.ScriptTargetResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

/** Resolves script targets in the client's copy of the current level and of the player's inventory. */
final class ClientScriptTargets implements ScriptTargetResolver {
    private final HeldStacks stacks = new HeldStacks();

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
            case SubjectRef.Stack stack -> {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null || !player.getUUID().equals(stack.player())) {
                    throw new IllegalStateException("The stack belongs to another player");
                }
                yield this.stacks.resolve(player, stack);
            }
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
