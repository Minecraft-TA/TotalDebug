package com.github.minecraft_ta.totaldebug.command.decompile;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class EntitySubCommand extends DecompileCommand.DecompileClassSubCommand {

    @Override
    public Class<?> getClassFromArg(@Nonnull String s) {
        return (Class<? extends Entity>) EntityList.stringToClassMapping.get(s);
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length != 1)
            return Collections.emptyList();
        return getListOfStringsMatchingLastWord(args, ((Set<String>) EntityList.stringToClassMapping.keySet()).toArray(new String[0]));
    }

    @Nonnull
    @Override
    public String getCommandName() {
        return "entity";
    }
}
