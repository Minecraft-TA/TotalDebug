package com.github.minecraft_ta.totaldebug.command.decompile;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.client.resources.I18n;
import net.minecraft.command.*;
import net.minecraft.util.*;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class DecompileCommand extends CommandBase {

    private final List<ICommand> subCommands = new ArrayList<>();

    public DecompileCommand() {
        subCommands.add(new ItemSubCommand());
        subCommands.add(new BlockSubCommand());
        subCommands.add(new EntitySubCommand());
        subCommands.add(new ClassSubCommand());
        subCommands.add(new EventListenerSubCommand());
    }

    @Nonnull
    @Override
    public String getCommandName() {
        return "decompile";
    }

    @Nonnull
    @Override
    public String getCommandUsage(@Nonnull ICommandSender sender) {
        return "";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(@Nonnull ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            IChatComponent component = new ChatComponentTranslation("commands.total_debug.decompile.usage")
                    .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GOLD))
                    .appendText("\n");

            for (Iterator<ICommand> iterator = this.subCommands.iterator(); iterator.hasNext(); ) {
                ICommand subCommand = iterator.next();

                String subCommandUsage = I18n.format("commands.total_debug.decompile." + subCommand.getCommandName() + ".usage");
                IChatComponent listStartComponent = new ChatComponentText("- ").setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY));

                component.appendSibling(
                        listStartComponent.appendSibling(
                                //subcommand name
                                new ChatComponentText(subCommand.getCommandName())
                                        .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY))
                                        .appendSibling(
                                                //subcommand args
                                                new ChatComponentText(
                                                        subCommandUsage.substring(StringUtils
                                                                .ordinalIndexOf(subCommandUsage, " ", 2))
                                                ).setChatStyle(new ChatStyle().setColor(EnumChatFormatting.WHITE))
                                        ).appendText(!iterator.hasNext() ? "" : "\n")
                        )
                );
            }

            sender.addChatMessage(component);
        } else {
            ICommand cmd = this.subCommands.stream()
                    .filter(c -> c.getCommandName().equals(args[0]))
                    .findFirst()
                    .orElse(null);

            if (cmd == null) {
                String subCommandsString = this.subCommands.stream()
                        .map(ICommand::getCommandName)
                        .reduce((s1, s2) -> s1 + ", " + s2)
                        .orElse("");
                throw new CommandException("Invalid subcommand %s. Available subcommands: %s", args[0], subCommandsString);
            } else if (!cmd.canCommandSenderUseCommand(sender)) {
                throw new CommandException("commands.generic.permission");
            } else {
                String[] newArgs = new String[args.length - 1];
                System.arraycopy(args, 1, newArgs, 0, newArgs.length);
                cmd.processCommand(sender, newArgs);
            }
        }
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length <= 1) {
            return getListOfStringsMatchingLastWord(args, this.subCommands.stream().map(ICommand::getCommandName).toArray(String[]::new));
        } else {
            ICommand cmd = this.subCommands.stream()
                    .filter(c -> c.getCommandName().equals(args[0]))
                    .findFirst()
                    .orElse(null);

            if (cmd != null) {
                String[] newArgs = new String[args.length - 1];
                System.arraycopy(args, 1, newArgs, 0, newArgs.length);
                return cmd.addTabCompletionOptions(sender, newArgs);
            }

            return null;
        }
    }

    public abstract static class DecompileClassSubCommand extends CommandBase {

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public void processCommand(@Nonnull ICommandSender sender, String[] args) throws WrongUsageException {
            if (args.length < 1)
                throw new WrongUsageException(getCommandUsage(sender));

            Class<?> clazz = getClassFromArg(args[0]);
            if (clazz == null) {
                sender.addChatMessage(new ChatComponentTranslation(getClassNotFoundTranslationKey(), args[0]));
                return;
            }

            TotalDebug.PROXY.getDecompilationManager().openGui(clazz);
        }

        public String getClassNotFoundTranslationKey() {
            return "commands.total_debug.decompile." + getCommandName() + ".failed";
        }

        @Nonnull
        @Override
        public String getCommandUsage(@Nonnull ICommandSender sender) {
            return "commands.total_debug.decompile." + getCommandName() + ".usage";
        }

        public abstract Class<?> getClassFromArg(@Nonnull String s);
    }
}
