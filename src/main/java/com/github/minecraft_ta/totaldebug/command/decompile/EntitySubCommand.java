package com.github.minecraft_ta.totaldebug.command.decompile;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.EntityEntry;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

public class EntitySubCommand extends DecompileCommand.DecompileClassSubCommand {

    @Override
    public Class<?> getClassFromArg(@Nonnull String s) {
        EntityEntry entry = ForgeRegistries.ENTITIES.getValue(new ResourceLocation(s));
        return entry != null ? entry.getEntityClass() : null;
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length != 1)
            return Collections.emptyList();
        return getListOfStringsMatchingLastWord(args, EntityList.getEntityNameList());
    }

    @Nonnull
    @Override
    public String getCommandName() {
        return "entity";
    }
}
