package com.github.tth05.codeviewer.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

import java.util.List;

public class CodeTextField extends Gui {

    private static final int PADDING = 5;

    private List<String> lines;

    private final int x, y, width, height;

    public CodeTextField(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void draw(FontRenderer fontRenderer, float scale, int startLine) {
        //border
        drawHorizontalLine(x, x + width, y, 0xFF000000);
        drawHorizontalLine(x, x + width, y + height, 0xFF000000);
        drawVerticalLine(x, y, y + height, 0xFF000000);
        drawVerticalLine(x + width, y, y + height, 0xFF000000);

        if (lines == null || lines.isEmpty())
            return;

        int visibleRows = getVisibleRows(scale);

        if (visibleRows > lines.size())
            visibleRows = lines.size();

        float lineY = y + PADDING;
        float lineX = x + PADDING;

        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 1);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        for (int i = startLine; i < Math.min(lines.size(), startLine + visibleRows); i++) {
            fontRenderer.drawString(
                    lines.get(i),
                    lineX / scale,
                    lineY / scale,
                    0xFFFFFFFF, false);

            lineY += 10f * scale;
        }

        GlStateManager.popMatrix();
    }

    public int getVisibleRows(float scale) {
        return (int) Math.ceil((this.height - PADDING * 2) / (10f * scale));
    }

    public void setLines(List<String> lines) {
        this.lines = lines;
    }

    public List<String> getLines() {
        return lines;
    }
}
