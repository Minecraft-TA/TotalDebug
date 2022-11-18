package com.github.minecraft_ta.totaldebug.render;

import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;

public class TickBlockTileRenderer extends TileEntitySpecialRenderer {

    @Override
    public void renderTileEntityAt(TileEntity te, double v, double v1, double v2, float v3) {
        /*this.setLightmapDisabled(true); TODO Fix please
        RenderHelper.drawTickBlockSideText(this.getFontRenderer(), te.getTicksPerSecond(), te.getAverage(), x, y, z);
        this.setLightmapDisabled(false);*/
    }
}
