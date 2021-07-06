package com.github.minecraft_ta.totaldebug.proxy;

import net.minecraftforge.fml.common.IFMLSidedHandler;
import net.minecraftforge.fml.server.FMLServerHandler;

public class ServerProxy extends CommonProxy {

    @Override
    public IFMLSidedHandler getSidedHandler() {
        return FMLServerHandler.instance();
    }
}
