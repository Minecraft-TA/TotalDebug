package com.github.minecraft_ta.totaldebug.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;

public class RenderHelper {

    /**
     * @see net.minecraft.client.renderer.EntityRenderer#drawNameplate(FontRenderer, String, float, float, float, int, float, float, boolean, boolean)
     */
    public static void drawNameplate(FontRenderer fontRendererIn, String str, float x, float y, float z, int verticalShift) {
        RenderManager renderManager = Minecraft.getMinecraft().getRenderManager();
        float viewerYaw = renderManager.playerViewY;
        float viewerPitch = renderManager.playerViewX;
        boolean isThirdPersonFrontal = renderManager.options.thirdPersonView == 2;

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.glNormal3f(0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-viewerYaw, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate((float) (isThirdPersonFrontal ? -1 : 1) * viewerPitch, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(-0.03F, -0.03F, 0.03F);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);

        GlStateManager.disableDepth();

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        int i = fontRendererIn.getStringWidth(str) / 2;
        GlStateManager.disableTexture2D();
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder bufferbuilder = tessellator.getBuffer();
        bufferbuilder.begin(7, DefaultVertexFormats.POSITION_COLOR);
        bufferbuilder.pos(-i - 1, -1 + verticalShift, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        bufferbuilder.pos(-i - 1, 8 + verticalShift, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        bufferbuilder.pos(i + 1, 8 + verticalShift, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        bufferbuilder.pos(i + 1, -1 + verticalShift, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        tessellator.draw();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();

        GlStateManager.enableDepth();

        GlStateManager.depthMask(true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        fontRendererIn.drawString(str, -fontRendererIn.getStringWidth(str) / 2, verticalShift, 553648127);
        GlStateManager.enableLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    public static void drawTickBlockSideText(FontRenderer fontRenderer, int tps, int average, double xIn, double y, double zIn) {
        for (EnumFacing side : EnumFacing.HORIZONTALS) {
            //move to center of block
            double x = xIn + 0.5f;
            double z = zIn + 0.5f;

            //1.0005 for depth test
            x += side.getXOffset() / 2f * 1.0005;
            z += side.getZOffset() / 2f * 1.0005;

            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y + 1f / fontRenderer.FONT_HEIGHT + 0.3f, z);
            GlStateManager.glNormal3f(0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(side.getAxis() == EnumFacing.Axis.Z ? side.getOpposite().getHorizontalIndex() * 90 : side.getHorizontalIndex() * 90, 0.0F, 1.0F, 0.0F);
            float scale = 0.026F;
            GlStateManager.scale(-scale, -scale, scale);
            GlStateManager.disableLighting();
            GlStateManager.enableTexture2D();

            String str = average + "";
            fontRenderer.drawString(str, -fontRenderer.getStringWidth(str) / 2f, 0, 0xFFFFFFFF, false);
            GlStateManager.translate(0, -0.36f / scale, 0);
            str = tps + "";
            fontRenderer.drawString(str, -fontRenderer.getStringWidth(str) / 2f, 0, 0xFFFFFFFF, false);

            GlStateManager.enableLighting();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }
}
