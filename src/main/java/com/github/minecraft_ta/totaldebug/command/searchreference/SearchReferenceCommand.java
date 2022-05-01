package com.github.minecraft_ta.totaldebug.command.searchreference;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.CompanionApp;
import com.github.minecraft_ta.totaldebug.companionApp.messages.search.OpenSearchResultsMessage;
import com.github.minecraft_ta.totaldebug.util.mappings.BytecodeReferenceSearcher;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
        if (args.length > 0 && args[0].equalsIgnoreCase("cancel")) {
            BytecodeReferenceSearcher.cancel();
            sender.sendMessage(new TextComponentTranslation("commands.total_debug.searchreference.cancel_success")
                    .setStyle(new Style().setColor(TextFormatting.GREEN)));
            return;
        }

        if (args.length < 2 || (!args[0].equalsIgnoreCase("field") && !args[0].equalsIgnoreCase("method"))) {
            throw new CommandException("commands.total_debug.searchreference.usage");
        }

        if (!args[1].matches("^([\\w/$]+\\.)?\\w[\\w/$();]+$")) {
            throw new CommandException("commands.total_debug.searchreference.usage_examples");
        }

        long t = System.nanoTime() / 1_000_000;

        boolean searchMethod = args[0].equalsIgnoreCase("method");

        int dotIndex = args[1].indexOf('.');
        String owner = dotIndex == -1 ? null : args[1].substring(0, dotIndex);
        String toMatch = dotIndex == -1 ? args[1] : args[1].substring(dotIndex + 1);
        CompletableFuture<Pair<Collection<String>, Integer>> future = BytecodeReferenceSearcher.findReferences(owner, toMatch, searchMethod);
        if (future == null) {
            throw new CommandException("commands.total_debug.searchreference.already_running");
        }

        future.exceptionally(e -> {
            //don't print stacktrace if task was cancelled
            if (!(e instanceof RejectedExecutionException))
                e.printStackTrace();
            return Pair.of(Collections.emptyList(), -1);
        }).thenAccept(resultPair -> {
            if (resultPair.getRight() == -1) {
                sender.sendMessage(new TextComponentString("There was an error during the scan. Please check " +
                                                           "the logs and report to mod authors.").setStyle(new Style().setColor(TextFormatting.RED)));
                return;
            }

            int scanTime = (int) (System.nanoTime() / 1_000_000 - t);

            if (TotalDebug.INSTANCE.config.useCompanionApp && resultPair.getLeft().size() > 0) {
                CompanionApp companionApp = TotalDebug.PROXY.getCompanionApp();
                companionApp.startAndConnect();

                if (companionApp.isConnected() && companionApp.waitForUI()) {
                    companionApp.getClient().getMessageProcessor().enqueueMessage(
                            new OpenSearchResultsMessage(args[1], resultPair.getLeft(), searchMethod, resultPair.getRight(), scanTime)
                    );
                }
            } else {
                sender.sendMessage(new TextComponentString("-------------------").setStyle(new Style().setColor(TextFormatting.GOLD)));

                int i = 0;
                for (String result : resultPair.getLeft()) {
                    sender.sendMessage(new TextComponentString(result)
                            .setStyle(new Style().setColor(i % 2 == 0 ? TextFormatting.WHITE : TextFormatting.GRAY)
                                    .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponentTranslation("commands.total_debug.searchreference.click_to_open")))
                                    .setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/decompile class " + result.split("#")[0].replace('/', '.')))));

                    i++;
                }

                sender.sendMessage(new TextComponentTranslation("commands.total_debug.searchreference.result_count", resultPair.getLeft().size())
                        .setStyle(new Style().setColor(TextFormatting.GREEN))
                        .appendText(", ")
                        .appendSibling(new TextComponentTranslation("commands.total_debug.searchreference.time", scanTime))
                        .appendText(", ")
                        .appendSibling(new TextComponentTranslation("commands.total_debug.searchreference.classes_count", resultPair.getRight())));
            }
        });
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "method", "field", "cancel");
        }
        return Collections.emptyList();
    }
}
