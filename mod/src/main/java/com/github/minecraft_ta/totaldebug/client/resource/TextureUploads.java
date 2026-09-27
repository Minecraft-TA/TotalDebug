package com.github.minecraft_ta.totaldebug.client.resource;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.google.gson.JsonObject;
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
import net.minecraft.util.GsonHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shows edited textures in the running game without reloading every resource. A texture of its own, such as an
 * entity's, is read again; a sprite of an atlas, such as a block's, gets the new pixels and mipmaps in its place in the
 * atlas, where models and animations already point, and a new animation where its {@code .mcmeta} changed but not the
 * size of a frame. A new frame size or sprite takes the full reload, and so does a texture the game does not read from
 * the pack it was saved into yet. Render thread only.
 */
final class TextureUploads {
    private TextureUploads() {
    }

    /**
     * Whether {@code path} is shown this way: a texture the game reads as an image, such as
     * {@code assets/ns/textures/block/gear.png}, or its animation beside it.
     */
    static boolean texture(String path) {
        String[] parts = path.split("/", 4);
        return parts.length == 4 && parts[0].equals("assets") && parts[2].equals("textures")
                && (path.endsWith(".png") || path.endsWith(".png.mcmeta"));
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
     * Shows the new pixels or animation of every texture of {@code paths}, which were saved into {@code pack}; returns
     * false where one needs the full reload, which then shows all of them.
     */
    static boolean upload(List<String> paths, String pack) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceManager resources = minecraft.getResourceManager();
        Map<ResourceLocation, AbstractTexture> textures = textures(minecraft.getTextureManager());
        if (textures == null) return false;
        Set<ResourceLocation> changed = new LinkedHashSet<>();
        // Textures whose .mcmeta was edited: shown this way only where their animation changed, and nothing else.
        Set<ResourceLocation> animated = new LinkedHashSet<>();
        for (String path : paths) {
            String[] parts = path.split("/", 3);
            ResourceLocation saved = ResourceLocation.tryBuild(parts[1], parts[2]);
            if (saved == null) return false;
            // A pack or namespace added since the last reload is not read yet; the copy shown would be another pack's.
            Optional<Resource> top = resources.getResource(saved);
            if (top.isEmpty() || !top.get().sourcePackId().equals(pack)) return false;
            if (!path.endsWith(".mcmeta")) {
                changed.add(saved);
                continue;
            }
            // Sections other than the animation, such as a mod's, may be read when the atlas is stitched.
            try (Reader reader = top.get().openAsReader()) {
                if (!onlyAnimation(reader)) return false;
            } catch (IOException unreadable) {
                return false;
            }
            ResourceLocation texture = saved.withPath(saved.getPath().substring(0, saved.getPath().length() - ".mcmeta".length()));
            changed.add(texture);
            animated.add(texture);
        }
        for (ResourceLocation texture : changed) {
            if (!upload(resources, textures, texture, animated.contains(texture))) return false;
        }
        return true;
    }

    /** Whether a texture's {@code .mcmeta} holds nothing but an animation. */
    static boolean onlyAnimation(Reader metadata) {
        try {
            JsonObject json = GsonHelper.parse(metadata);
            return json.keySet().equals(Set.of(AnimationMetadataSection.SECTION_NAME));
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** {@code animated} tells that the texture's {@code .mcmeta} was edited, which must have changed its animation. */
    private static boolean upload(ResourceManager resources, Map<ResourceLocation, AbstractTexture> textures, ResourceLocation location,
                                  boolean animated) {
        Optional<Resource> resource = resources.getResource(location);
        if (resource.isEmpty()) return false;
        boolean shown = false;
        boolean own = textures.get(location) instanceof SimpleTexture texture && texture.getClass() == SimpleTexture.class;
        // A texture of its own reads its .mcmeta for other sections than an animation, which the full reload covers.
        if (own && animated) return false;
        if (own && textures.get(location) instanceof SimpleTexture texture) {
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
            if (!upload(atlas, atlas.getTextures().get(sprite), resource.get(), animated)) return false;
            shown = true;
        }
        return shown;
    }

    /**
     * Puts the resource into the sprite's place in its atlas: its pixels where only they changed, a new animation where
     * the frames did but not their size; false where the size changed.
     */
    private static boolean upload(TextureAtlas atlas, TextureAtlasSprite sprite, Resource resource, boolean animated) {
        SpriteContents contents = sprite.contents();
        NativeImage original = contents.getOriginalImage();
        ResourceMetadata metadata;
        NativeImage image;
        try (InputStream input = resource.open()) {
            // The metadata first, so an unreadable .mcmeta leaves no image to free.
            metadata = resource.metadata();
            image = NativeImage.read(input);
        } catch (IOException | RuntimeException exception) {
            TotalDebug.LOGGER.warn("Texture {} could not be read", contents.name(), exception);
            return false;
        }
        try {
            boolean sameAnimation = animation(metadata, image).equals(animation(contents.metadata(), original));
            // An edited .mcmeta with the same animation changed something else, which only the full reload shows.
            if (animated && sameAnimation) {
                image.close();
                return false;
            }
            if (image.getWidth() == original.getWidth() && image.getHeight() == original.getHeight() && sameAnimation) {
                original.copyFrom(image);
                image.close();
                // The smaller levels are made from the new pixels, as when the atlas was stitched; the first level is the image.
                NativeImage[] previous = contents.byMipLevel;
                contents.byMipLevel = MipmapGenerator.generateMipLevels(new NativeImage[]{original}, previous.length - 1);
                for (int level = 1; level < previous.length; level++) previous[level].close();
                atlas.bind();
                // An animation's next frame is uploaded from the new pixels on the next tick.
                sprite.uploadFirstFrame();
                return true;
            }
            FrameSize frame = metadata.getSection(AnimationMetadataSection.SERIALIZER).orElse(AnimationMetadataSection.EMPTY)
                    .calculateFrameSize(image.getWidth(), image.getHeight());
            // A frame of another size needs another place in the atlas, which only stitching it again gives.
            if (frame.width() != contents.width() || frame.height() != contents.height()) {
                image.close();
                return false;
            }
            return animate(atlas, sprite, new SpriteContents(contents.name(), frame, image, metadata), contents.byMipLevel.length - 1);
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.warn("Texture {} could not be put into its atlas", contents.name(), exception);
            image.close();
            return false;
        }
    }

    /**
     * Gives the sprite {@code fresh} contents, with a new animation, in its place: the atlas's animations start over with
     * it. False, with nothing changed, where the game's fields cannot be reached.
     */
    @SuppressWarnings("unchecked")
    private static boolean animate(TextureAtlas atlas, TextureAtlasSprite sprite, SpriteContents fresh, int mipLevel) {
        Field contentsField;
        Field spritesField;
        Field tickersField;
        List<SpriteContents> sprites;
        List<TextureAtlasSprite.Ticker> tickers;
        try {
            contentsField = field(TextureAtlasSprite.class, "contents");
            spritesField = field(TextureAtlas.class, "sprites");
            tickersField = field(TextureAtlas.class, "animatedTextures");
            sprites = (List<SpriteContents>) spritesField.get(atlas);
            tickers = (List<TextureAtlasSprite.Ticker>) tickersField.get(atlas);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            TotalDebug.LOGGER.warn("The atlas {} cannot be given a new animation; it takes a full reload", atlas.location(), exception);
            fresh.close();
            return false;
        }
        fresh.increaseMipLevel(mipLevel);
        SpriteContents previous = sprite.contents();
        try {
            contentsField.set(sprite, fresh);
            List<SpriteContents> replaced = new ArrayList<>(sprites);
            replaced.replaceAll(contents -> contents == previous ? fresh : contents);
            spritesField.set(atlas, List.copyOf(replaced));
            // Each ticker plays the contents it was made for, so the atlas makes them again, as when it was stitched.
            List<TextureAtlasSprite.Ticker> made = new ArrayList<>();
            for (TextureAtlasSprite each : atlas.getTextures().values()) {
                TextureAtlasSprite.Ticker ticker = each.createTicker();
                if (ticker != null) made.add(ticker);
            }
            tickersField.set(atlas, List.copyOf(made));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        tickers.forEach(TextureAtlasSprite.Ticker::close);
        previous.close();
        atlas.bind();
        // Every animation starts over at its first frame, which is shown now, as when the atlas was stitched.
        for (TextureAtlasSprite each : atlas.getTextures().values()) {
            if (each == sprite || each.contents().getUniqueFrames().count() > 1) each.uploadFirstFrame();
        }
        return true;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
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
            return Map.copyOf((Map<ResourceLocation, AbstractTexture>) field(TextureManager.class, "byPath").get(manager));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            TotalDebug.LOGGER.warn("The game's textures could not be listed; edited textures take a full reload", exception);
            return null;
        }
    }
}
