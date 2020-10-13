package com.github.tth05.codeviewer.handler;


import com.github.tth05.codeviewer.CodeViewer;
import com.github.tth05.codeviewer.gui.CodeViewScreen;
import com.github.tth05.codeviewer.network.DecompilationResultMessage;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

public class PlayerInteractionHandler {

    @SubscribeEvent
    public void onClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getSide() == Side.CLIENT) {
            FMLClientHandler.instance().showGuiScreen(new CodeViewScreen());
            return;
        }

        if (!CodeViewer.INSTANCE.decompilationManager.isSetupComplete())
            return;

        Class<?> clazz = event.getWorld().getBlockState(event.getPos()).getBlock().getClass();

        CodeViewer.INSTANCE.decompilationManager.getDecompiledFile(clazz).thenAccept((lines) ->
                CodeViewer.INSTANCE.network.sendTo(new DecompilationResultMessage(clazz.getName(), lines),
                        (EntityPlayerMP) event.getEntityPlayer())
        );
    }
}
