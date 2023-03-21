package com.github.minecraft_ta.totaldebug.nei.imageexport;

import codechicken.core.CommonUtils;
import codechicken.lib.gui.GuiDraw;
import codechicken.nei.NEIClientUtils;
import codechicken.nei.config.Option;
import codechicken.nei.guihook.GuiContainerManager;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Objects;

public class ItemStackDumper extends GuiScreen {

    private int drawIndex;
    private int parseIndex;
    private final int iconSize;
    private final int borderSize;
    private final int boxSize;
    private final Option option;
    private final List<ItemStack> items;
    private final File dir = new File(CommonUtils.getMinecraftDir(), "dumps/recipe_icons");
    private IntBuffer pixelBuffer;
    private int[] pixelValues;

    public ItemStackDumper(Option option, List<ItemStack> items, int iconSize) {
        this.option = option;
        this.items = items;
        this.iconSize = iconSize;
        borderSize = iconSize / 16;
        boxSize = iconSize + borderSize * 2;

        if (dir.exists()) {
            for (File f : dir.listFiles()) if (f.isFile()) f.delete();
        } else dir.mkdirs();
    }

    @Override
    public void drawScreen(int mousex, int mousey, float frame) {
        try {
            drawItems();
            exportItems();
        } catch (Exception e) {
            TotalDebug.LOGGER.error("Error while dumping item panel icons", e);
        }
    }

    private void drawItems() {
        Dimension d = GuiDraw.displayRes();

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0D, d.width * 16D / iconSize, d.height * 16D / iconSize, 0.0D, 1000.0D, 3000.0D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        int rows = d.height / boxSize;
        int cols = d.width / boxSize;
        int fit = rows * cols;

        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glColor4f(1, 1, 1, 1);

        for (int i = 0; drawIndex < items.size() && i < fit; drawIndex++, i++) {
            int x = i % cols * 18;
            int y = i / cols * 18;
            ItemStack itemstack = items.get(drawIndex);
            itemstack.stackSize = 1;
            GuiContainerManager.drawItem(x + 1, y + 1, itemstack);
        }

        GL11.glFlush();
    }

    private void exportItems() throws IOException {
        BufferedImage img = screenshot();
        int rows = img.getHeight() / boxSize;
        int cols = img.getWidth() / boxSize;
        int fit = rows * cols;

        for (int i = 0; parseIndex < items.size() && i < fit; parseIndex++, i++) {
            int x = i % cols * boxSize;
            int y = i / cols * boxSize;
            exportImage(
                    dir, img.getSubimage(x + borderSize, y + borderSize, iconSize, iconSize), items.get(parseIndex));
        }

        if (parseIndex >= items.size()) {
            NEIClientUtils.printChatMessage((new ChatComponentText(EnumChatFormatting.GREEN + "Dumped " + parseIndex + " icons to " + dir.getPath())));
            // Display the normal gui again
            Minecraft.getMinecraft().displayGuiScreen(this.option.slot.getGui());
        }
    }

    private void exportImage(File dir, BufferedImage img, ItemStack stack) throws IOException {
        NBTTagCompound nbt = stack.getTagCompound();
        int hash = Objects.hash(stack.getUnlocalizedName(), stack.getItemDamage(), nbt == null ? 0 : nbt.toString());
        File file = new File(dir, hash + ".png");
        ImageIO.write(img, "png", file);
    }

    private BufferedImage screenshot() {
        Framebuffer fb = Minecraft.getMinecraft().getFramebuffer();
        Dimension mcSize = GuiDraw.displayRes();
        Dimension texSize = mcSize;

        if (OpenGlHelper.isFramebufferEnabled())
            texSize = new Dimension(fb.framebufferTextureWidth, fb.framebufferTextureHeight);

        int k = texSize.width * texSize.height;
        if (pixelBuffer == null || pixelBuffer.capacity() < k) {
            pixelBuffer = BufferUtils.createIntBuffer(k);
            pixelValues = new int[k];
        }

        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        pixelBuffer.clear();

        if (OpenGlHelper.isFramebufferEnabled()) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, fb.framebufferTexture);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
        } else {
            GL11.glReadPixels(
                    0, 0, texSize.width, texSize.height, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
        }

        pixelBuffer.get(pixelValues);
        TextureUtil.func_147953_a(pixelValues, texSize.width, texSize.height);

        BufferedImage img = new BufferedImage(mcSize.width, mcSize.height, BufferedImage.TYPE_INT_ARGB);
        if (OpenGlHelper.isFramebufferEnabled()) {
            int yOff = texSize.height - mcSize.height;
            for (int y = 0; y < mcSize.height; ++y)
                for (int x = 0; x < mcSize.width; ++x) img.setRGB(x, y, pixelValues[(y + yOff) * texSize.width + x]);
        } else {
            img.setRGB(0, 0, texSize.width, height, pixelValues, 0, texSize.width);
        }

        return img;
    }


}
