package com.github.minecraft_ta.totaldebug.command.searchreference;

import com.github.minecraft_ta.totaldebug.util.mappings.MethodReferenceSearcher;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

public class SearchReferenceCommand extends CommandBase {

    @Nonnull
    @Override
    public String getName() {
        return "searchreference";
    }

    @Nonnull
    @Override
    public String getUsage(@Nonnull ICommandSender sender) {
        return "";
    }

    @Override
    public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args) throws CommandException {
        if (args.length < 2 || (!args[0].equalsIgnoreCase("field") &&
                !args[0].equalsIgnoreCase("method") &&
                !args[0].equalsIgnoreCase("cancel"))) {
            throw new CommandException("commands.total_debug.searchreference.usage");
        }

        if (args[0].equalsIgnoreCase("cancel")) {
            MethodReferenceSearcher.cancel();
            sender.sendMessage(new TextComponentTranslation("commands.total_debug.searchreference.cancel_success")
                    .setStyle(new Style().setColor(TextFormatting.GREEN)));
            return;
        }

        long t = System.nanoTime() / 1_000_000;

        boolean searchMethod = args[0].equalsIgnoreCase("method");
        CompletableFuture<Collection<String>> future = MethodReferenceSearcher.findMethodReferences(args[1], searchMethod);
        if (future == null) {
            throw new CommandException("commands.total_debug.searchreference.already_running");
        }

        future.exceptionally(e -> {
            //don't print stacktrace if task was cancelled
            if (!(e instanceof RejectedExecutionException))
                e.printStackTrace();
            return Collections.emptyList();
        }).thenAccept(results -> {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                sender.sendMessage(new TextComponentString("-------------------").setStyle(new Style().setColor(TextFormatting.GOLD)));

                int i = 0;
                for (String result : results) {
                    sender.sendMessage(new TextComponentString(result)
                            .setStyle(new Style().setColor(i % 2 == 0 ? TextFormatting.WHITE : TextFormatting.GRAY)
                                    .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponentTranslation("commands.total_debug.searchreference.click_to_open")))
                            .setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/decompile class " + result.split("#")[0].replace('/', '.')))));

                    i++;
                }
                sender.sendMessage(new TextComponentTranslation("commands.total_debug.searchreference.result_count", results.size())
                        .setStyle(new Style().setColor(TextFormatting.GREEN))
                        .appendText(" ")
                        .appendSibling(new TextComponentTranslation("commands.total_debug.searchreference.time", System.nanoTime() / 1_000_000 - t)));
            });
        });
    }
}
