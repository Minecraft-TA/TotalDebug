package com.github.minecraft_ta.totaldebug.render;

import com.github.minecraft_ta.totaldebug.block.tile.TickBlockTile;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;

public class TickBlockTileRenderer extends TileEntitySpecialRenderer<TickBlockTile> {

    @Override
    public void render(TickBlockTile te, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
        this.setLightmapDisabled(true);
        RenderHelper.drawTickBlockSideText(this.getFontRenderer(), te.getTicksPerSecond(), te.getAverage(), x, y, z);
        this.setLightmapDisabled(false);
    }

    @Override
    public void renderTileEntityAt(TileEntity tileEntity, double v, double v1, double v2, float v3) {
        this.
    }
}
