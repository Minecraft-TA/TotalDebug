package com.github.minecraft_ta.totaldebug.command.decompile;

import net.minecraft.command.ICommandSender;
import net.minecraft.item.Item;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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
        return getListOfStringsMatchingLastWord(args, ((Set<String>) Item.itemRegistry.getKeys()).toArray(new String[0]));
    }

    @Nonnull
    @Override
    public String getCommandName() {
        return "item";
    }
}
