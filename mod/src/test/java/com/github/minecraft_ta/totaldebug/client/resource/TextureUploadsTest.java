package com.github.minecraft_ta.totaldebug.client.resource;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureUploadsTest {
    @Test
    void onlyTexturesAreShownTheQuickWay() {
        assertTrue(TextureUploads.texture("assets/testmod/textures/block/gear.png"));
        assertTrue(TextureUploads.texture("assets/testmod/textures/entity/gear/spinner.png"));
        assertTrue(TextureUploads.texture("assets/testmod/textures/block/gear.png.mcmeta"), "a texture's animation too");
        assertFalse(TextureUploads.texture("assets/testmod/textures/block/gear.json"));
        assertFalse(TextureUploads.texture("assets/testmod/lang/en_us.json"));
        assertFalse(TextureUploads.texture("assets/testmod/textures.png"));
    }

    @Test
    void onlyAnAnimationIsShownTheQuickWay() {
        assertTrue(TextureUploads.onlyAnimation(new StringReader("{\"animation\":{\"frametime\":2}}")));
        assertFalse(TextureUploads.onlyAnimation(new StringReader("{\"animation\":{},\"texture\":{\"blur\":true}}")),
                "other sections, such as a mod's, may be read when the atlas is stitched");
        assertFalse(TextureUploads.onlyAnimation(new StringReader("not json")));
    }

    @Test
    void aTextureIsLookedForInTheAtlasesUnderTheNameTheirFoldersGiveIt() {
        assertEquals(ResourceLocation.fromNamespaceAndPath("testmod", "block/gear"),
                TextureUploads.spriteOf(ResourceLocation.fromNamespaceAndPath("testmod", "textures/block/gear.png")));
        assertEquals(ResourceLocation.fromNamespaceAndPath("minecraft", "entity/chest/normal"),
                TextureUploads.spriteOf(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/chest/normal.png")));
    }
}
