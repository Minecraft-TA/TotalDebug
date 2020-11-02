package com.github.tth05.codeviewer.handler;


import com.github.tth05.codeviewer.CodeViewer;
import com.github.tth05.codeviewer.HitType;
import com.github.tth05.codeviewer.KeyBindings;
import com.github.tth05.codeviewer.network.DecompilationRequestMessage;
import com.github.tth05.codeviewer.network.LoadedRequestMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;

public class KeyInputHandler {

    @SubscribeEvent
    public void onKeyPress(InputEvent.KeyInputEvent event) {
        if (KeyBindings.CODE_GUI.isKeyDown()) {
            rayTraceEyes();
        } else if (KeyBindings.LOADED_GUI.isKeyDown()) {
            CodeViewer.INSTANCE.network.sendToServer(new LoadedRequestMessage());
        }
    }

    //TODO Raytrace code
    public void rayTraceEyes() {
        RayTraceResult rayTraceResult = Minecraft.getMinecraft().objectMouseOver;
        WorldClient world = Minecraft.getMinecraft().world;


        switch (rayTraceResult.typeOfHit) {
            case BLOCK:
                BlockPos blockPos = rayTraceResult.getBlockPos();
                if (!world.isAirBlock(blockPos)) {
                    if (world.getTileEntity(blockPos) == null) {
                        CodeViewer.INSTANCE.network.sendToServer(new DecompilationRequestMessage(HitType.BLOCK_ENTITY, blockPos));
                    } else {
                        CodeViewer.INSTANCE.network.sendToServer(new DecompilationRequestMessage(HitType.TILE_ENTITY, blockPos));
                    }
                }
                break;
            case ENTITY:
                CodeViewer.INSTANCE.network.sendToServer(new DecompilationRequestMessage(HitType.LIVING_ENTITY, rayTraceResult.entityHit.getEntityId()));
                break;
        }

    }
}
