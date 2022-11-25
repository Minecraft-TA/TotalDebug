package com.github.minecraft_ta.totaldebug.render;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.EnumFacing;
import org.lwjgl.opengl.GL11;

public class RenderHelper {

    private static final EnumFacing[] HORIZONTALS = new EnumFacing[]{EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};
    private static final int[] X_OFFSETS = new int[]{0, 0, -1, 1};
    private static final int[] Z_OFFSETS = new int[]{-1, 1, 0, 0};
    private static final int[] HORIZONTAL_INDEX = new int[]{2, 0, 1, 3};
    private static final int[] HORIZONTAL_INDEX_OPPOSITE = new int[]{0, 2, 3, 1};

    public static void drawTickBlockSideText(FontRenderer fontRenderer, int tps, int average, double xIn, double y, double zIn) {
        for (int i = 0; i < HORIZONTALS.length; i++) {
            EnumFacing side = HORIZONTALS[i];

            //move to center of block
            double x = xIn + 0.5f;
            double z = zIn + 0.5f;

            //1.0005 for depth test
            x += X_OFFSETS[i] / 2f * 1.0005;
            z += Z_OFFSETS[i] / 2f * 1.0005;

            GL11.glPushMatrix();
            GL11.glTranslatef((float) x, (float) (y + 1f / fontRenderer.FONT_HEIGHT + 0.3f), (float) z);
            GL11.glNormal3f(0.0F, 1.0F, 0.0F);
            GL11.glRotatef(i <= 1 ? HORIZONTAL_INDEX_OPPOSITE[i] * 90 : HORIZONTAL_INDEX[i] * 90, 0.0F, 1.0F, 0.0F);
            float scale = 0.026F;
            GL11.glScalef(-scale, -scale, scale);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_TEXTURE_2D);

            String str = average + "";
            fontRenderer.drawString(str, -fontRenderer.getStringWidth(str) / 2, 0, 0xFFFFFFFF, false);
            GL11.glTranslatef(0, -0.36f / scale, 0);
            str = tps + "";
            fontRenderer.drawString(str, -fontRenderer.getStringWidth(str) / 2, 0, 0xFFFFFFFF, false);

            GL11.glEnable(GL11.GL_LIGHTING);
            GL11.glClearColor(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glPopMatrix();
        }
    }
}
