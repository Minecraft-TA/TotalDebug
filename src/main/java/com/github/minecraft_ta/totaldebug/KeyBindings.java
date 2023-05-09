package com.github.minecraft_ta.totaldebug;

import cpw.mods.fml.client.registry.ClientRegistry;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

public class KeyBindings {

    public static final KeyBinding CODE_GUI =
            new KeyBinding("key." + Tags.MODID + ".openCodeGui", Keyboard.KEY_F6, Tags.MODNAME);

    public static void init() {
        ClientRegistry.registerKeyBinding(CODE_GUI);
    }
}
