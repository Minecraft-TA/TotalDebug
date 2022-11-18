package com.github.minecraft_ta.totaldebug.render;

import com.github.minecraft_ta.totaldebug.block.tile.TickBlockTile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;

public class TickBlockTileRenderer extends TileEntitySpecialRenderer {

    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float v3) {
        Minecraft.getMinecraft().entityRenderer.disableLightmap(0);
        TickBlockTile tile = (TickBlockTile) te;
        RenderHelper.drawTickBlockSideText(Minecraft.getMinecraft().fontRenderer, tile.getTicksPerSecond(), tile.getAverage(), x, y, z);
        Minecraft.getMinecraft().entityRenderer.enableLightmap(0);
    }
}
