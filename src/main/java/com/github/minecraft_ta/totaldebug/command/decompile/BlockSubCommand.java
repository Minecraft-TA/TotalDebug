package com.github.minecraft_ta.totaldebug.command.decompile;

import net.minecraft.block.Block;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class BlockSubCommand extends DecompileCommand.DecompileClassSubCommand {

    @Override
    public Class<?> getClassFromArg(@Nonnull String s) {
        ResourceLocation key = new ResourceLocation(s);
        return Block.blockRegistry.containsKey(key) ? Block.blockRegistry.getObject(key).getClass() : null;
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length != 1)
            return Collections.emptyList();
        return getListOfStringsMatchingLastWord(args, ((Set<Block>) Block.blockRegistry.getKeys()).stream()
                .map(k -> k.getUnlocalizedName().substring(5)).toArray(String[]::new));
    }

    @Nonnull
    @Override
    public String getCommandName() {
        return "block";
    }

}
