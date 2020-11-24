package com.github.minecraft_ta.totaldebug;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.Keyboard;

public class KeyBindings {

    public static final KeyBinding CODE_GUI =
            new KeyBinding("key." + TotalDebug.MOD_ID + ".openCodeGui", KeyConflictContext.IN_GAME, Keyboard.KEY_F6, TotalDebug.MOD_NAME);

    public static void init() {
        ClientRegistry.registerKeyBinding(CODE_GUI);
    }
}
