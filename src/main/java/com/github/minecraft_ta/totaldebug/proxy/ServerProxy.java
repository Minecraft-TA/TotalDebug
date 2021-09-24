package com.github.minecraft_ta.totaldebug.proxy;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.IFMLSidedHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.server.FMLServerHandler;

import java.util.List;

public class ServerProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent e) {
        super.init(e);
        MinecraftForge.EVENT_BUS.register(new Object() {
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
        });
    }

    @Override
    public IFMLSidedHandler getSidedHandler() {
        return FMLServerHandler.instance();
    }
}
