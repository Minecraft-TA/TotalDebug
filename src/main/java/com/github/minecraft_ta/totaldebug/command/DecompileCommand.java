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
import net.minecraftforge.client.IClientCommand;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.IEventListener;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class DecompileCommand extends CommandBase implements IClientCommand {

    private final HashMap<String, ArrayList<Class<?>>> eventsToListeners = new HashMap<>();

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
            return getListOfStringsMatchingLastWord(args, "item", "block", "entity", "classpath", "eventlistener");
        } else if (args.length >= 2) {
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
                case "eventlistener":
                    if (eventsToListeners.isEmpty()) {
                        loadEventCache();
                    }
                    if (args.length == 3) {
                        return getListOfStringsMatchingLastWord(args, eventsToListeners.get(args[1]).stream().map(Class::getName).collect(Collectors.toList()));
                    }
                    return getListOfStringsMatchingLastWord(args, new ArrayList<>(eventsToListeners.keySet()));
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

    private void loadEventCache() {
        try {
            Field field = MinecraftForge.EVENT_BUS.getClass().getDeclaredField("listeners");
            field.setAccessible(true);

            @SuppressWarnings("unchecked")
            ConcurrentHashMap<Object, ArrayList<IEventListener>> listeners = (ConcurrentHashMap<Object, ArrayList<IEventListener>>) field.get(MinecraftForge.EVENT_BUS);

            listeners.keySet().forEach(e -> {
                Method[] methods = e.getClass().getMethods();
                for (Method m : methods) {
                    if (m.isAnnotationPresent(SubscribeEvent.class)) {
                        Class<?>[] parameter = m.getParameterTypes();
                        Class<?> enclosingClass = parameter[0].getEnclosingClass();
                        eventsToListeners.computeIfAbsent(enclosingClass != null ? enclosingClass.getSimpleName() + "$" + parameter[0].getSimpleName() : parameter[0].getSimpleName(), aClass -> new ArrayList<>()).add(e.getClass());
                    }
                }
            });
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
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
                case "eventlistener":
                    if (eventsToListeners.isEmpty()) {
                        loadEventCache();
                    }
                    ArrayList<Class<?>> classes = eventsToListeners.get(args[1]);
                    if (classes != null) {
                        if (args.length == 3) {
                            classes.forEach(c ->{
                                if (c.getName().equals(args[2])) {
                                    TotalDebug.PROXY.getDecompilationManager().openGui(c);
                                }
                            });
                        }else {
                            TotalDebug.PROXY.getDecompilationManager().openGui(classes.get(0));
                        }
                    }
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

    @Override
    public boolean allowUsageWithoutPrefix(ICommandSender sender, String message) {
        return false;
    }
}
