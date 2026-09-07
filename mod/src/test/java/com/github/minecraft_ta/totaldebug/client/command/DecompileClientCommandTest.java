package com.github.minecraft_ta.totaldebug.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecompileClientCommandTest {
    @Test
    void registersAndExecutesEveryTargetKind() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        RecordingResolver resolver = new RecordingResolver();
        List<Class<?>> openedClasses = new ArrayList<>();
        DecompileClientCommand.register(dispatcher, resolver, openedClasses::add);

        var decompileNode = dispatcher.getRoot().getChild("decompile");
        assertEquals(
                Set.of("block", "item", "entity", "blockentity", "class"),
                decompileNode.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet())
        );

        assertEquals(1, dispatcher.execute("decompile block minecraft:lever", null));
        assertEquals(1, dispatcher.execute("decompile item minecraft:diamond", null));
        assertEquals(1, dispatcher.execute("decompile entity minecraft:cow", null));
        assertEquals(1, dispatcher.execute("decompile blockentity minecraft:chest", null));
        assertEquals(1, dispatcher.execute("decompile class net.minecraft.world.level.block.Block", null));

        var suggestions = dispatcher.getCompletionSuggestions(
                dispatcher.parse("decompile class net.mine", null)
        ).get();
        assertEquals(
                List.of("net.minecraft"),
                suggestions.getList().stream().map(suggestion -> suggestion.getText()).toList()
        );

        assertEquals(
                List.of(BlockTarget.class, ItemTarget.class, EntityTarget.class, BlockEntityTarget.class, NamedTarget.class),
                openedClasses
        );
        assertEquals(
                List.of(
                        "block:minecraft:lever",
                        "item:minecraft:diamond",
                        "entity:minecraft:cow",
                        "blockentity:minecraft:chest",
                        "class:net.minecraft.world.level.block.Block"
                ),
                resolver.requests
        );
    }

    private static final class RecordingResolver implements DecompileClientCommand.TargetResolver {
        private final List<String> requests = new ArrayList<>();

        @Override
        public Collection<ResourceLocation> blockIds() {
            return List.of(ResourceLocation.parse("minecraft:lever"));
        }

        @Override
        public Optional<Class<?>> block(ResourceLocation id) {
            this.requests.add("block:" + id);
            return Optional.of(BlockTarget.class);
        }

        @Override
        public Collection<ResourceLocation> itemIds() {
            return List.of(ResourceLocation.parse("minecraft:diamond"));
        }

        @Override
        public Optional<Class<?>> item(ResourceLocation id) {
            this.requests.add("item:" + id);
            return Optional.of(ItemTarget.class);
        }

        @Override
        public Collection<ResourceLocation> entityIds() {
            return List.of(ResourceLocation.parse("minecraft:cow"));
        }

        @Override
        public Optional<Class<?>> entity(ResourceLocation id) {
            this.requests.add("entity:" + id);
            return Optional.of(EntityTarget.class);
        }

        @Override
        public Collection<ResourceLocation> blockEntityIds() {
            return List.of(ResourceLocation.parse("minecraft:chest"));
        }

        @Override
        public Optional<Class<?>> blockEntity(ResourceLocation id) {
            this.requests.add("blockentity:" + id);
            return Optional.of(BlockEntityTarget.class);
        }

        @Override
        public CompletableFuture<List<String>> suggestedClasses(String input) {
            assertEquals("net.mine", input);
            return CompletableFuture.completedFuture(List.of("net.minecraft"));
        }

        @Override
        public Optional<Class<?>> namedClass(String binaryName) {
            this.requests.add("class:" + binaryName);
            return Optional.of(NamedTarget.class);
        }
    }

    private static final class BlockTarget {
    }

    private static final class ItemTarget {
    }

    private static final class EntityTarget {
    }

    private static final class BlockEntityTarget {
    }

    private static final class NamedTarget {
    }
}
