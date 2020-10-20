package com.github.tth05.codeviewer.handler;


import com.github.tth05.codeviewer.CodeViewer;
import com.github.tth05.codeviewer.HitType;
import com.github.tth05.codeviewer.KeyBindings;
import com.github.tth05.codeviewer.network.DecompilationRequestMessage;
import com.github.tth05.codeviewer.network.LoadedRequestMessage;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;

public class KeyInputHandler {

    @SubscribeEvent
    public void onKeyPress(InputEvent.KeyInputEvent event) {
        if (KeyBindings.CODE_GUI.isKeyDown()) {
            CodeViewer.INSTANCE.network.sendToServer(new DecompilationRequestMessage(HitType.BLOCK_ENTITY, new BlockPos(0, 0, 0)));
        } else if (KeyBindings.LOADED_GUI.isKeyDown()) {
            CodeViewer.INSTANCE.network.sendToServer(new LoadedRequestMessage());
        }
    }

    //TODO Raytrace code
    public void rayTraceEyes() {

    }
}
