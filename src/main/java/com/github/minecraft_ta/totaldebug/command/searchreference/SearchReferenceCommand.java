package com.github.minecraft_ta.totaldebug.command.searchreference;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.CompanionApp;
import com.github.minecraft_ta.totaldebug.companionApp.messages.search.OpenSearchResultsMessage;
import com.github.minecraft_ta.totaldebug.util.mappings.BytecodeReferenceSearch;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

public class SearchReferenceCommand extends CommandBase {

    private BytecodeReferenceSearch search;

    @Nonnull
    @Override
    public String getCommandName() {
        return "searchreference";
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
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 2 || (!args[0].equalsIgnoreCase("field") && !args[0].equalsIgnoreCase("method"))) {
            throw new CommandException("commands.total_debug.searchreference.usage");
        }

        if (!args[1].matches("^([\\w/$]+\\.)?" + "[\\w<>]+" + "\\([\\w/$;]*\\)" + "[\\w/$;]+$")) {
            throw new CommandException("commands.total_debug.searchreference.usage_examples");
        }

        boolean searchMethod = args[0].equalsIgnoreCase("method");

        int dotIndex = args[1].indexOf('.');
        String owner = dotIndex == -1 ? null : args[1].substring(0, dotIndex);
        String toMatch = dotIndex == -1 ? args[1] : args[1].substring(dotIndex + 1);

        if (this.search != null)
            this.search.cancelIfRunning();

        long startTime = System.nanoTime() / 1_000_000;

        this.search = new BytecodeReferenceSearch(owner, toMatch, searchMethod)
                .withResultHandler(result -> {
                    int scanTime = (int) (System.nanoTime() / 1_000_000 - startTime);

                    if (TotalDebug.INSTANCE.config.useCompanionApp && result.getLeft().size() > 0) {
                        CompanionApp companionApp = TotalDebug.PROXY.getCompanionApp();
                        companionApp.startAndConnect();

                        if (companionApp.isConnected() && companionApp.waitForUI()) {
                            companionApp.getClient().getMessageProcessor().enqueueMessage(
                                    new OpenSearchResultsMessage(args[1], result.getLeft(), searchMethod, result.getRight(), scanTime)
                            );
                        }
                    } else {
                        sender.addChatMessage(new ChatComponentText("-------------------").setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GOLD)));

                        int i = 0;
                        for (String s : result.getLeft()) {
                            sender.addChatMessage(new ChatComponentText(s)
                                    .setChatStyle(new ChatStyle().setColor(i % 2 == 0 ? EnumChatFormatting.WHITE : EnumChatFormatting.GRAY)));
                            //.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentTranslation("commands.total_debug.searchreference.click_to_open")))
                            //.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/decompile class " + result.split("#")[0].replace('/', '.')))));

                            i++;
                        }

                        sender.addChatMessage(new ChatComponentTranslation("commands.total_debug.searchreference.result_count", result.getLeft().size())
                                .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GREEN))
                                .appendText(", ")
                                .appendSibling(new ChatComponentTranslation("commands.total_debug.searchreference.time", scanTime))
                                .appendText(", ")
                                .appendSibling(new ChatComponentTranslation("commands.total_debug.searchreference.classes_count", result.getRight())));
                    }
                })
                .withProgressHandler((current, total) -> {
                    sender.addChatMessage(new ChatComponentTranslation("commands.total_debug.searchreference.progress", current, total)
                            .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GREEN)));
                })
                .withThrowableHandler(t -> {
                    TotalDebug.LOGGER.error(String.format("Error during reference search %s, %s, %s", owner, toMatch, searchMethod), t);
                    sender.addChatMessage(new ChatComponentText("There was an error during the scan. Please check " +
                                                                "the logs and report to mod authors.").setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED)));
                })
                .withCancellationHandler(() -> {
                    sender.addChatMessage(new ChatComponentTranslation("commands.total_debug.searchreference.cancel_success")
                            .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GOLD)));
                })
                .run();
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "method", "field", "cancel");
        }
        return Collections.emptyList();
    }
}
