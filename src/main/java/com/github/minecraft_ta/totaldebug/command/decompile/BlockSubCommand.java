package com.github.minecraft_ta.totaldebug.command.decompile;

import net.minecraft.block.Block;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

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
        //TODO: Not sure
        return getListOfStringsMatchingLastWord(args, String.valueOf(Block.blockRegistry.getKeys()));
    }

    @Nonnull
    @Override
    public String getCommandName() {
        return "block";
    }

}
