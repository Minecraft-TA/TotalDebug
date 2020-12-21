package com.github.minecraft_ta.totaldebug.command;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import io.github.classgraph.*;
import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.item.Item;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class DecompileCommand extends CommandBase {

    @Override
    public String getName() {
        return "decompile";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.total_debug.decompile.usage";
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "item", "block", "entity", "classpath");
        } else if (args.length == 2) {
            switch (args[0]) {
                case "classpath":
                    String path = args[1];
                    if (path.isEmpty())
                        return Collections.emptyList();

                    int dotCount = StringUtils.countMatches(path, '.');

                    int lastIndexOfDot = path.lastIndexOf('.');
                    if (lastIndexOfDot != -1)
                        path = path.substring(0, lastIndexOfDot);

                    try (ScanResult result = new ClassGraph()
                            .acceptPackages(path + "*")
                            .enableClassInfo().scan()) {

                        ClassInfoList classInfo = result.getAllClasses();
                        PackageInfoList packageInfo = result.getPackageInfo();

                        List<String> options = new LinkedList<>();
                        for (ClassInfo info : classInfo) {
                            if (StringUtils.countMatches(info.getName(), '.') <= dotCount)
                                options.add(info.getName());
                        }
                        for (PackageInfo info : packageInfo) {
                            if (StringUtils.countMatches(info.getName(), '.') <= dotCount)
                                options.add(info.getName());
                        }

                        return getListOfStringsMatchingLastWord(args, options);
                    }
                case "item":
                    return getListOfStringsMatchingLastWord(args, Item.REGISTRY.getKeys());
                case "block":
                    return getListOfStringsMatchingLastWord(args, Block.REGISTRY.getKeys());
                case "entity":
                    return getListOfStringsMatchingLastWord(args, EntityList.getEntityNameList());
            }
        }
        return Collections.emptyList();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            throw new WrongUsageException("commands.total_debug.decompile.usage");
        } else {
            switch (args[0]) {
                case "item":
                    TotalDebug.PROXY.getDecompilationManager().openGui(getItemByText(sender, args[1]).getClass());
                    break;
                case "block":
                    TotalDebug.PROXY.getDecompilationManager().openGui(getBlockByText(sender, args[1]).getClass());
                    break;
                case "entity":
                    TotalDebug.PROXY.getDecompilationManager().openGui(getEntityClassByText(sender, args[1]));
                    break;
                case "classpath":
                    try {
                        TotalDebug.PROXY.getDecompilationManager().openGui(Class.forName(args[1]));
                    } catch (ClassNotFoundException e) {
                        throw new CommandException("commands.total_debug.decompile.path.failed");
                    }
                    break;
            }
        }
    }

    public Class<? extends Entity> getEntityClassByText(ICommandSender sender, String id) throws CommandException {
        ResourceLocation resourcelocation = new ResourceLocation(id);
        EntityEntry entry = ForgeRegistries.ENTITIES.getValue(resourcelocation);
        if (entry != null) {
            return entry.getEntityClass();
        }
        throw new CommandException("commands.total_debug.decompile.entity.failed", resourcelocation);
    }
}
