package com.github.minecraft_ta.totaldebug.proxy;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.IFMLSidedHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.server.FMLServerHandler;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ServerProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent e) {
        super.init(e);
        FMLCommonHandler.instance().bus().register(new GlobalTickHandler());
    }

    @Override
    public Path getMinecraftClassDumpPath() {
        return Paths.get(".").resolve("total-debug").resolve("minecraft-class-dump.jar");
    }

    @Override
    public IFMLSidedHandler getSidedHandler() {
        return FMLServerHandler.instance();
    }

    public class GlobalTickHandler {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            List<Runnable> tasks;
            if (event.phase == TickEvent.Phase.START)
                tasks = ServerProxy.super.preTickTasks;
            else
                tasks = ServerProxy.super.postTickTasks;

            synchronized (tasks) {
                tasks.forEach(Runnable::run);
                tasks.clear();
            }
        }
    }
}
