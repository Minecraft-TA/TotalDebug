package com.github.minecraft_ta.totaldebug.proxy;

import com.github.minecraft_ta.totaldebug.KeyBindings;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent e) {
        super.init(e);

        KeyBindings.init();
    }
}
