package com.github.tth05.codeviewer;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.Keyboard;

public class KeyBindings {

    public static final KeyBinding OPEN_GUI =
            new KeyBinding("key.code_debug.openGui", KeyConflictContext.IN_GAME, Keyboard.KEY_L, "Code Debug");

    public static void init() {
        ClientRegistry.registerKeyBinding(OPEN_GUI);
    }
}
