package com.github.minecraft_ta.totaldebug.command.decompile;

import net.minecraft.command.ICommandSender;
import net.minecraft.item.Item;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class ItemSubCommand extends DecompileCommand.DecompileClassSubCommand {

    @Nullable
    @Override
    public Class<?> getClassFromArg(@Nonnull String s) {
        Item item = Item.REGISTRY.getObject(new ResourceLocation(s));
        return item != null ? item.getClass() : null;
    }

    @Nonnull
    @Override
    public List<String> getTabCompletions(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
                                          @Nonnull String[] args, @Nullable BlockPos targetPos) {
        if (args.length != 1)
            return Collections.emptyList();
        return getListOfStringsMatchingLastWord(args, Item.REGISTRY.getKeys());
    }

    @Nonnull
    @Override
    public String getName() {
        return "item";
    }
}
