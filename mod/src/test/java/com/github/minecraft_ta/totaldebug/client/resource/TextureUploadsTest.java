package com.github.minecraft_ta.totaldebug.client.resource;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureUploadsTest {
    @Test
    void onlyTexturesAreShownTheQuickWay() {
        assertTrue(TextureUploads.texture("assets/testmod/textures/block/gear.png"));
        assertTrue(TextureUploads.texture("assets/testmod/textures/entity/gear/spinner.png"));
        assertFalse(TextureUploads.texture("assets/testmod/textures/block/gear.png.mcmeta"), "a new animation takes the full reload");
        assertFalse(TextureUploads.texture("assets/testmod/lang/en_us.json"));
        assertFalse(TextureUploads.texture("assets/testmod/textures.png"));
    }

    @Test
    void aTextureIsLookedForInTheAtlasesUnderTheNameTheirFoldersGiveIt() {
        assertEquals(ResourceLocation.fromNamespaceAndPath("testmod", "block/gear"),
                TextureUploads.spriteOf(ResourceLocation.fromNamespaceAndPath("testmod", "textures/block/gear.png")));
        assertEquals(ResourceLocation.fromNamespaceAndPath("minecraft", "entity/chest/normal"),
                TextureUploads.spriteOf(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/chest/normal.png")));
    }
}
