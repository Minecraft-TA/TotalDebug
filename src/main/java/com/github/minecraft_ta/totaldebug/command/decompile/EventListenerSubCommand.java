package com.github.minecraft_ta.totaldebug.command.decompile;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import cpw.mods.fml.common.eventhandler.IEventListener;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraftforge.common.MinecraftForge;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public class EventListenerSubCommand extends CommandBase {

    private final Map<String, ArrayList<Class<?>>> eventsToListeners = new HashMap<>();

    private void initCache() {
        if (!eventsToListeners.isEmpty())
            return;

        Field field;
        try {
            field = MinecraftForge.EVENT_BUS.getClass().getDeclaredField("listeners");
            field.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("listeners field not found");
        }

        Map<Object, ArrayList<IEventListener>> listeners;
        try {
            //noinspection unchecked
            listeners = (Map<Object, ArrayList<IEventListener>>) field.get(MinecraftForge.EVENT_BUS);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("listeners field is not accessible");
        }

        for (Object e : listeners.keySet()) {
            Method[] methods = e.getClass().getMethods();
            for (Method m : methods) {
                if (!m.isAnnotationPresent(SubscribeEvent.class))
                    continue;

                Class<?> eventClass = m.getParameterTypes()[0];
                Class<?> enclosingClass = eventClass.getEnclosingClass();
                String fullEventClassName = enclosingClass != null ?
                        enclosingClass.getSimpleName() + "$" + eventClass.getSimpleName() :
                        eventClass.getSimpleName();
                eventsToListeners.computeIfAbsent(fullEventClassName, c -> new ArrayList<>()).add(e.getClass());
            }
        }
    }

    @Override
    public void processCommand(@Nonnull ICommandSender sender, @Nonnull String[] args) throws CommandException {
        if (args.length < 1)
            throw new WrongUsageException("commands.total_debug.decompile.eventlistener.usage");

        initCache();

        ArrayList<Class<?>> classes = eventsToListeners.get(args[0]);
        if (classes == null) {
            throw new CommandException("commands.total_debug.decompile.eventlistener.failed_event", args[0]);
        }

        if (args.length == 2) {
            Optional<Class<?>> optionalClass = classes.stream().filter(c -> c.getName().equals(args[1])).findFirst();
            if (!optionalClass.isPresent())
                throw new CommandException("commands.total_debug.decompile.class.failed", args[1]);

            TotalDebug.PROXY.getDecompilationManager().openGui(optionalClass.get());
        } else {
            TotalDebug.PROXY.getDecompilationManager().openGui(classes.get(0));
        }
    }
    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length < 1 || args.length > 2)
            return Collections.emptyList();

        initCache();

        if (args.length == 2) {
            ArrayList<Class<?>> classes = eventsToListeners.get(args[0]);
            if (classes == null)
                return Collections.emptyList();

            return getListOfStringsMatchingLastWord(args, classes.stream().map(Class::getName).toArray(String[]::new));
        }

        return getListOfStringsMatchingLastWord(args, eventsToListeners.keySet().toArray(new String[0]));
    }

    @Nonnull
    @Override
    public String getCommandUsage(@Nonnull ICommandSender sender) {
        return "";
    }

    @Nonnull
    @Override
    public String getCommandName() {
        return "eventlistener";
    }
}
