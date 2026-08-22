package com.github.minecraft_ta.totaldebug.client;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

@EventBusSubscriber(modid = TotalDebug.MOD_ID, value = Dist.CLIENT)
final class DecompileClientCommand {
    private DecompileClientCommand() {
    }

    @SubscribeEvent
    static void register(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("decompile")
                .then(Commands.literal("block")
                        .then(Commands.argument("block", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        BuiltInRegistries.BLOCK.keySet(),
                                        builder
                                ))
                                .executes(DecompileClientCommand::decompileBlock))));
    }

    private static int decompileBlock(CommandContext<CommandSourceStack> context) {
        ResourceLocation blockId = ResourceLocationArgument.getId(context, "block");
        if (!BuiltInRegistries.BLOCK.containsKey(blockId)) {
            context.getSource().sendFailure(Component.translatable(
                    "commands.total_debug.decompile.block.failed",
                    blockId
            ));
            return 0;
        }

        TotalDebugClientRuntime.decompilation().openClass(BuiltInRegistries.BLOCK.get(blockId).getClass());
        return 1;
    }
}
