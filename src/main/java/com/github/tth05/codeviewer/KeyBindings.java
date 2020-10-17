package com.github.tth05.codeviewer;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.Keyboard;

public class KeyBindings {

    public static final KeyBinding CODE_GUI =
            new KeyBinding("key.code_debug.openCodeGui", KeyConflictContext.IN_GAME, Keyboard.KEY_F6, "Code Debug");
    public static final KeyBinding LOADED_GUI =
            new KeyBinding("key.code_debug.openEntityGui", KeyConflictContext.IN_GAME, Keyboard.KEY_F7, "Code Debug");


    public static void init() {
        ClientRegistry.registerKeyBinding(CODE_GUI);
        ClientRegistry.registerKeyBinding(LOADED_GUI);
    }
}
