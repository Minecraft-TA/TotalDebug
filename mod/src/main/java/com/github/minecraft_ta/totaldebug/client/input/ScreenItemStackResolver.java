package com.github.minecraft_ta.totaldebug.client.input;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

@FunctionalInterface
public interface ScreenItemStackResolver {
    Optional<ItemStack> resolve(Screen screen, double mouseX, double mouseY);
}
