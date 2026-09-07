package com.github.minecraft_ta.totaldebug.client.command;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.TotalDebugClient;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

@EventBusSubscriber(modid = TotalDebug.MOD_ID, value = Dist.CLIENT)
final class DecompileClientCommand {
    private DecompileClientCommand() {
    }

    @SubscribeEvent
    static void register(RegisterClientCommandsEvent event) {
        register(
                event.getDispatcher(),
                DecompileTargetResolver.runtime(),
                targetClass -> TotalDebugClient.get().openClass(targetClass)
        );
    }

    static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            TargetResolver resolver,
            Consumer<Class<?>> classOpener
    ) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("decompile");
        root.then(resourceSubcommand(
                "block",
                resolver.blockIds(),
                resolver::block,
                classOpener
        ));
        root.then(resourceSubcommand(
                "item",
                resolver.itemIds(),
                resolver::item,
                classOpener
        ));
        root.then(resourceSubcommand(
                "entity",
                resolver.entityIds(),
                resolver::entity,
                classOpener
        ));
        root.then(resourceSubcommand(
                "blockentity",
                resolver.blockEntityIds(),
                resolver::blockEntity,
                classOpener
        ));
        root.then(Commands.literal("class")
                .then(Commands.argument("class", StringArgumentType.word())
                        .suggests((context, builder) -> resolver.suggestedClasses(builder.getRemaining())
                                .thenCompose(suggestions -> SharedSuggestionProvider.suggest(suggestions, builder)))
                        .executes(context -> open(
                                context,
                                "class",
                                StringArgumentType.getString(context, "class"),
                                resolver.namedClass(StringArgumentType.getString(context, "class")),
                                classOpener
                        ))));
        dispatcher.register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> resourceSubcommand(
            String name,
            Collection<ResourceLocation> suggestions,
            Function<ResourceLocation, Optional<Class<?>>> resolver,
            Consumer<Class<?>> classOpener
    ) {
        return Commands.literal(name)
                .then(Commands.argument(name, ResourceLocationArgument.id())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(suggestions, builder))
                        .executes(context -> {
                            ResourceLocation id = ResourceLocationArgument.getId(context, name);
                            return open(context, name, id, resolver.apply(id), classOpener);
                        }));
    }

    private static int open(
            CommandContext<CommandSourceStack> context,
            String targetKind,
            Object requestedTarget,
            Optional<Class<?>> targetClass,
            Consumer<Class<?>> classOpener
    ) {
        if (targetClass.isEmpty()) {
            context.getSource().sendFailure(Component.translatable(
                    "commands.total_debug.decompile." + targetKind + ".failed",
                    requestedTarget
            ));
            return 0;
        }

        classOpener.accept(targetClass.get());
        return 1;
    }

    interface TargetResolver {
        Collection<ResourceLocation> blockIds();

        Optional<Class<?>> block(ResourceLocation id);

        Collection<ResourceLocation> itemIds();

        Optional<Class<?>> item(ResourceLocation id);

        Collection<ResourceLocation> entityIds();

        Optional<Class<?>> entity(ResourceLocation id);

        Collection<ResourceLocation> blockEntityIds();

        Optional<Class<?>> blockEntity(ResourceLocation id);

        CompletableFuture<List<String>> suggestedClasses(String input);

        Optional<Class<?>> namedClass(String binaryName);
    }
}
