package com.github.tth05.codeviewer.gui;

import com.github.tth05.codeviewer.util.CodeHighlighter;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.List;

public class CodeViewScreen extends GuiScreen {

    private String filename;
    private List<String> lines;

    float scale = 1f;

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);

        drawRect(0, 0, this.width, this.height, 0xFF282C34);

        if(lines == null)
            return;


        boolean prev = this.fontRenderer.getUnicodeFlag();
        this.fontRenderer.setUnicodeFlag(false);

        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 1);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        int y = 5;
        for (String line : lines) {
            this.fontRenderer.drawString(line, Math.round(5f / scale), y, 0xFFFFFFFF);
            y += 9;
        }

        GlStateManager.popMatrix();

        this.fontRenderer.setUnicodeFlag(prev);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if(mouseButton == 0)
            scale += .05f;
        else
            scale -= .05f;
    }

    public void setLines(List<String> lines) {
        this.lines = lines;
        CodeHighlighter.highlightJavaCode(lines);
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }
}
