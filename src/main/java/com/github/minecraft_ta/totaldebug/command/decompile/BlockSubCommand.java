package com.github.minecraft_ta.totaldebug.command.decompile;

import net.minecraft.block.Block;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class BlockSubCommand extends DecompileCommand.DecompileClassSubCommand {

    @Nullable
    @Override
    public Class<?> getClassFromArg(@Nonnull String s) {
        ResourceLocation key = new ResourceLocation(s);
        return Block.REGISTRY.containsKey(key) ? Block.REGISTRY.getObject(key).getClass() : null;
    }

    @Nonnull
    @Override
    public List<String> getTabCompletions(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
                                          @Nonnull String[] args, @Nullable BlockPos targetPos) {
        if (args.length != 1)
            return Collections.emptyList();
        return getListOfStringsMatchingLastWord(args, Block.REGISTRY.getKeys());
    }

    @Nonnull
    @Override
    public String getName() {
        return "block";
    }

}
