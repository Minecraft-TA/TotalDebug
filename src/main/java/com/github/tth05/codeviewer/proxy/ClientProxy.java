package com.github.tth05.codeviewer.proxy;

import com.github.tth05.codeviewer.KeyBindings;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent e) {
        super.init(e);

        KeyBindings.init();
    }
}
