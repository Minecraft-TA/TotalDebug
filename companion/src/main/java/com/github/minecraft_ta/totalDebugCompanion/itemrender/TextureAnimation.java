package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The {@code animation} section of a texture's {@code .mcmeta}: the frame size and the frames in the order the game
 * plays them. Frame sheets are read row by row, as the game reads them.
 */
public record TextureAnimation(int frameWidth, int frameHeight, int columns, int rows, List<Frame> frames) {

    /** One played frame: its index in the sheet and how many game ticks it stays. */
    public record Frame(int index, int ticks) {
    }

    public TextureAnimation {
        frames = List.copyOf(frames);
    }

    /** The animation described by {@code metadata}, or empty when it has no {@code animation} section. */
    public static Optional<TextureAnimation> read(byte[] metadata, int imageWidth, int imageHeight) {
        JsonObject root = JsonParser.parseString(new String(metadata, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!root.has("animation")) {
            return Optional.empty();
        }
        return Optional.of(parse(root.getAsJsonObject("animation"), imageWidth, imageHeight));
    }

    /** Parses an {@code animation} object for an image of the given size; frames may name indices outside the sheet. */
    public static TextureAnimation parse(JsonObject animation, int imageWidth, int imageHeight) {
        int frameWidth = optionalInt(animation, "width", -1);
        int frameHeight = optionalInt(animation, "height", -1);
        if (frameWidth < 1 && frameHeight < 1) {
            frameWidth = Math.min(imageWidth, imageHeight);
            frameHeight = frameWidth;
        } else if (frameWidth < 1) {
            frameWidth = imageWidth;
        } else if (frameHeight < 1) {
            frameHeight = imageHeight;
        }
        if (frameWidth < 1 || frameHeight < 1 || imageWidth % frameWidth != 0 || imageHeight % frameHeight != 0) {
            throw new JsonParseException("animation frame size does not divide the texture");
        }
        int columns = imageWidth / frameWidth;
        int rows = imageHeight / frameHeight;
        int frameTime = Math.max(1, optionalInt(animation, "frametime", 1));
        List<Frame> frames = new ArrayList<>();
        if (animation.has("frames")) {
            JsonElement declared = animation.get("frames");
            if (!declared.isJsonArray()) {
                throw new JsonParseException("frames must be an array");
            }
            for (JsonElement frame : (JsonArray) declared) {
                if (frame.isJsonObject()) {
                    JsonObject object = frame.getAsJsonObject();
                    if (!object.has("index")) {
                        throw new JsonParseException("missing index");
                    }
                    frames.add(new Frame(object.get("index").getAsInt(),
                            Math.max(1, optionalInt(object, "time", frameTime))));
                } else {
                    frames.add(new Frame(frame.getAsInt(), frameTime));
                }
            }
        }
        if (frames.isEmpty()) {
            for (int index = 0; index < columns * rows; index++) {
                frames.add(new Frame(index, frameTime));
            }
        }
        return new TextureAnimation(frameWidth, frameHeight, columns, rows, frames);
    }

    /**
     * The top square of an image taller than wide by a whole number of squares, which is how animated textures store
     * their frames; any other image as it is. Used where no metadata says how the frames are laid out.
     */
    public static BufferedImage firstFrameOfStrip(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        return height > width && height % width == 0 ? image.getSubimage(0, 0, width, width) : image;
    }

    public boolean contains(int index) {
        return index >= 0 && index < this.columns * this.rows;
    }

    /** Where frame {@code index} lies in the sheet. */
    public Rectangle region(int index) {
        if (!contains(index)) {
            throw new IllegalArgumentException("animation frame index is outside the texture");
        }
        return new Rectangle(index % this.columns * this.frameWidth, index / this.columns * this.frameHeight,
                this.frameWidth, this.frameHeight);
    }

    private static int optionalInt(JsonObject json, String name, int fallback) {
        return json.has(name) ? json.get(name).getAsInt() : fallback;
    }
}
