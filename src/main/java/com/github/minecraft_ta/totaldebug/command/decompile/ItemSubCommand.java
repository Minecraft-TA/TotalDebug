package com.github.minecraft_ta.totaldebug.command.decompile;

import net.minecraft.command.ICommandSender;
import net.minecraft.item.Item;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

public class ItemSubCommand extends DecompileCommand.DecompileClassSubCommand {

    @Override
    public Class<?> getClassFromArg(@Nonnull String s) {
        Item item = (Item) Item.itemRegistry.getObject(s);
        return item != null ? item.getClass() : null;
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length != 1)
            return Collections.emptyList();
        //TODO: Check if this works
        return getListOfStringsMatchingLastWord(args, String.valueOf(Item.itemRegistry.getKeys()));
    }

    @Nonnull
    @Override
    public String getCommandName() {
        return "item";
    }
}
