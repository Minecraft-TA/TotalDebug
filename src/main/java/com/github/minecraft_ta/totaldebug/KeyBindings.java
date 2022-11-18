package com.github.minecraft_ta.totaldebug;

import cpw.mods.fml.client.registry.ClientRegistry;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

public class KeyBindings {

    public static final KeyBinding CODE_GUI =
            new KeyBinding("key." + TotalDebug.MOD_ID + ".openCodeGui", Keyboard.KEY_F6, TotalDebug.MOD_NAME);

    public static void init() {
        ClientRegistry.registerKeyBinding(CODE_GUI);
    }
}
