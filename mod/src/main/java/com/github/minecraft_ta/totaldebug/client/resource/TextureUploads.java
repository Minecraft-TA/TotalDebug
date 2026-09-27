package com.github.minecraft_ta.totaldebug.client.resource;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shows edited textures in the running game without reloading every resource. A texture of its own, such as an
 * entity's, is read again; a sprite of an atlas, such as a block's, gets the new pixels and mipmaps in its place in the
 * atlas, where models and animations already point. Only a change of pixels is shown this way: a new size, animation or
 * sprite takes the full reload, and so does a texture the game does not read from the pack it was saved into yet.
 * Render thread only.
 */
final class TextureUploads {
    private TextureUploads() {
    }

    /** Whether {@code path} is a texture the game reads as an image, such as {@code assets/ns/textures/block/gear.png}. */
    static boolean texture(String path) {
        String[] parts = path.split("/", 4);
        return parts.length == 4 && parts[0].equals("assets") && parts[2].equals("textures") && path.endsWith(".png");
    }

    /**
     * The sprite an atlas holds a texture as, the way the game's atlases list a folder of textures: its path without
     * {@code textures/} and {@code .png}, such as {@code ns:block/gear}. An atlas that names its sprites otherwise has
     * none by that name, and the texture takes the full reload.
     */
    static ResourceLocation spriteOf(ResourceLocation texture) {
        String path = texture.getPath();
        return ResourceLocation.fromNamespaceAndPath(texture.getNamespace(),
                path.substring("textures/".length(), path.length() - ".png".length()));
    }

    /**
     * Shows the new pixels of every texture of {@code paths}, read from {@code pack}; returns false where one needs the
     * full reload, which then shows all of them.
     */
    static boolean upload(List<String> paths, String pack) {
        Minecraft minecraft = Minecraft.getInstance();
        Map<ResourceLocation, AbstractTexture> textures = textures(minecraft.getTextureManager());
        if (textures == null) return false;
        for (String path : paths) {
            String[] parts = path.split("/", 3);
            ResourceLocation location = ResourceLocation.tryBuild(parts[1], parts[2]);
            if (location == null || !upload(minecraft.getResourceManager(), textures, location, pack)) return false;
        }
        return true;
    }

    private static boolean upload(ResourceManager resources, Map<ResourceLocation, AbstractTexture> textures,
                                  ResourceLocation location, String pack) {
        // A pack or namespace added since the last reload is not read yet; the copy shown would be another pack's.
        Optional<Resource> resource = resources.getResource(location);
        if (resource.isEmpty() || !resource.get().sourcePackId().equals(pack)) return false;
        boolean shown = false;
        if (textures.get(location) instanceof SimpleTexture texture && texture.getClass() == SimpleTexture.class) {
            try {
                texture.load(resources);
            } catch (IOException exception) {
                TotalDebug.LOGGER.warn("Texture {} could not be read again", location, exception);
                return false;
            }
            shown = true;
        }
        ResourceLocation sprite = spriteOf(location);
        for (AbstractTexture texture : textures.values()) {
            if (!(texture instanceof TextureAtlas atlas) || !atlas.getTextures().containsKey(sprite)) continue;
            if (!upload(atlas, atlas.getTextures().get(sprite), resource.get())) return false;
            shown = true;
        }
        return shown;
    }

    /** Puts the resource's pixels into the sprite's place in its atlas; false when more than the pixels changed. */
    private static boolean upload(TextureAtlas atlas, TextureAtlasSprite sprite, Resource resource) {
        SpriteContents contents = sprite.contents();
        NativeImage original = contents.getOriginalImage();
        try (InputStream input = resource.open(); NativeImage image = NativeImage.read(input)) {
            if (image.getWidth() != original.getWidth() || image.getHeight() != original.getHeight()) return false;
            if (!animation(resource.metadata(), image).equals(animation(contents.metadata(), original))) return false;
            original.copyFrom(image);
        } catch (IOException | RuntimeException exception) {
            TotalDebug.LOGGER.warn("Texture {} could not be put into its atlas", contents.name(), exception);
            return false;
        }
        // The smaller levels are made from the new pixels, as when the atlas was stitched; the first level is the image.
        NativeImage[] previous = contents.byMipLevel;
        contents.byMipLevel = MipmapGenerator.generateMipLevels(new NativeImage[]{original}, previous.length - 1);
        for (int level = 1; level < previous.length; level++) previous[level].close();
        atlas.bind();
        // An animation's next frame is uploaded from the new pixels on the next tick.
        sprite.uploadFirstFrame();
        return true;
    }

    /** The animation a texture declares, as a text that is equal for the same frames, sizes and times. */
    static String animation(ResourceMetadata metadata, NativeImage image) {
        AnimationMetadataSection animation = metadata.getSection(AnimationMetadataSection.SERIALIZER)
                .orElse(AnimationMetadataSection.EMPTY);
        FrameSize size = animation.calculateFrameSize(image.getWidth(), image.getHeight());
        StringBuilder text = new StringBuilder().append(size.width()).append('x').append(size.height())
                .append(' ').append(animation.getDefaultFrameTime()).append(' ').append(animation.isInterpolatedFrames());
        animation.forEachFrame((index, time) -> text.append(' ').append(index).append(':').append(time));
        return text.toString();
    }

    /** Every texture the game holds, by location, or null when they cannot be reached. */
    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, AbstractTexture> textures(TextureManager manager) {
        try {
            Field textures = TextureManager.class.getDeclaredField("byPath");
            textures.setAccessible(true);
            return Map.copyOf((Map<ResourceLocation, AbstractTexture>) textures.get(manager));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            TotalDebug.LOGGER.warn("The game's textures could not be listed; edited textures take a full reload", exception);
            return null;
        }
    }
}
