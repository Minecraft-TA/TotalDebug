package com.github.minecraft_ta.totaldebug.command.decompile;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.client.resources.I18n;
import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.*;
import net.minecraftforge.client.IClientCommand;
import net.minecraftforge.server.command.CommandTreeBase;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import java.util.Iterator;

public class DecompileCommand extends CommandTreeBase implements IClientCommand {

    public DecompileCommand() {
        addSubcommand(new ItemSubCommand());
        addSubcommand(new BlockSubCommand());
        addSubcommand(new EntitySubCommand());
        addSubcommand(new ClassSubCommand());
        addSubcommand(new EventListenerSubCommand());
    }

    @Nonnull
    @Override
    public String getName() {
        return "decompile";
    }

    @Nonnull
    @Override
    public String getUsage(@Nonnull ICommandSender sender) {
        return "";
    }

    @Override
    public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            ITextComponent component = new TextComponentTranslation("commands.total_debug.decompile.usage")
                    .setStyle(new Style().setColor(TextFormatting.GOLD))
                    .appendText("\n");

            for (Iterator<ICommand> iterator = this.getSubCommands().iterator(); iterator.hasNext(); ) {
                ICommand subCommand = iterator.next();

                String subCommandUsage = I18n.format("commands.total_debug.decompile." + subCommand.getName() + ".usage");
                ITextComponent listStartComponent = new TextComponentString("- ").setStyle(new Style().setColor(TextFormatting.DARK_GRAY));

                component.appendSibling(
                        listStartComponent.appendSibling(
                                //subcommand name
                                new TextComponentString(subCommand.getName())
                                        .setStyle(new Style().setColor(TextFormatting.GRAY))
                                        .appendSibling(
                                                //subcommand args
                                                new TextComponentString(
                                                        subCommandUsage.substring(StringUtils
                                                                .ordinalIndexOf(subCommandUsage, " ", 2))
                                                ).setStyle(new Style().setColor(TextFormatting.WHITE))
                                        ).appendText(!iterator.hasNext() ? "" : "\n")
                        )
                );
            }

            sender.sendMessage(component);
        } else {
            super.execute(server, sender, args);
        }
    }

    @Override
    public boolean allowUsageWithoutPrefix(ICommandSender sender, String message) {
        return false;
    }

    public abstract static class DecompileClassSubCommand extends CommandBase {

        @Override
        public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, String[] args) throws WrongUsageException {
            if (args.length < 1)
                throw new WrongUsageException(getUsage(sender));

            Class<?> clazz = getClassFromArg(args[0]);
            if (clazz == null) {
                sender.sendMessage(new TextComponentTranslation(getClassNotFoundTranslationKey(), args[0]));
                return;
            }

            TotalDebug.PROXY.getDecompilationManager().openGui(clazz);
        }

        public String getClassNotFoundTranslationKey() {
            return "commands.total_debug.decompile." + getName() + ".failed";
        }

        @Nonnull
        @Override
        public String getUsage(@Nonnull ICommandSender sender) {
            return "commands.total_debug.decompile." + getName() + ".usage";
        }

        public abstract Class<?> getClassFromArg(@Nonnull String s);
    }
}
