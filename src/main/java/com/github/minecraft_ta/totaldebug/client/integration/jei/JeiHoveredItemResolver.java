package com.github.minecraft_ta.totaldebug.client.integration.jei;

import com.github.minecraft_ta.totaldebug.client.input.ScreenItemStackResolver;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

final class JeiHoveredItemResolver implements ScreenItemStackResolver {
    private final IJeiRuntime runtime;

    JeiHoveredItemResolver(IJeiRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public Optional<ItemStack> resolve(Screen screen, double mouseX, double mouseY) {
        return resolveIngredient(this.runtime, screen, mouseX, mouseY, JeiHoveredItemResolver::asItemStack);
    }

    static <T> Optional<T> resolveIngredient(
            IJeiRuntime runtime,
            Screen screen,
            double mouseX,
            double mouseY,
            Function<ITypedIngredient<?>, Optional<T>> converter
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(converter, "converter");
        return convert(runtime.getIngredientListOverlay().getIngredientUnderMouse(), converter)
                .or(() -> convert(runtime.getBookmarkOverlay().getIngredientUnderMouse(), converter))
                .or(() -> runtime.getScreenHelper()
                        .getClickableIngredientUnderMouse(screen, mouseX, mouseY)
                        .map(clickable -> converter.apply(clickable.getTypedIngredient()))
                        .flatMap(Optional::stream)
                        .findFirst());
    }

    private static <T> Optional<T> convert(
            Optional<ITypedIngredient<?>> ingredient,
            Function<ITypedIngredient<?>, Optional<T>> converter
    ) {
        return ingredient.flatMap(converter);
    }

    private static Optional<ItemStack> asItemStack(ITypedIngredient<?> ingredient) {
        return ingredient.getItemStack()
                .filter(stack -> !stack.isEmpty());
    }
}
