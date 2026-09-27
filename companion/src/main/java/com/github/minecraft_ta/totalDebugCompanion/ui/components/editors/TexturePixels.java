package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pixels of a texture being edited, as one sheet of every animation frame, and the strokes that changed them. A
 * stroke, such as one drag of the pencil or one fill, is undone and redone whole.
 */
final class TexturePixels {
    /** The pixels a stroke changed, by index into the sheet, with their colors before and after. */
    private record Stroke(int[] pixels, int[] before, int[] after) {
    }

    private BufferedImage sheet;
    private final Deque<Stroke> undone = new ArrayDeque<>();
    private final Deque<Stroke> done = new ArrayDeque<>();
    /** How many pixels have each color that is not fully transparent, kept up to date by every change. */
    private final Map<Integer, Integer> counts = new HashMap<>();
    /** The colors before the stroke under way changed them, by pixel index in the order they changed; null between strokes. */
    private Map<Integer, Integer> stroke;

    /** Edits a copy of {@code image}. */
    TexturePixels(BufferedImage image) {
        replace(image);
    }

    /** The sheet, which strokes change in place. */
    BufferedImage sheet() {
        return this.sheet;
    }

    /** Edits a copy of {@code image} from now on, with no strokes to undo. */
    void replace(BufferedImage image) {
        this.sheet = copy(image);
        this.done.clear();
        this.undone.clear();
        this.stroke = null;
        this.counts.clear();
        int width = this.sheet.getWidth();
        int[] row = new int[width];
        for (int y = 0; y < this.sheet.getHeight(); y++) {
            this.sheet.getRGB(0, y, width, 1, row, 0, width);
            for (int argb : row) count(argb, 1);
        }
    }

    int color(int x, int y) {
        return this.sheet.getRGB(x, y);
    }

    /** Starts a stroke; the changes until {@link #end()} are undone as one. */
    void begin() {
        this.stroke = new LinkedHashMap<>();
    }

    /** Whether a stroke is under way. */
    boolean drawing() {
        return this.stroke != null;
    }

    /** Sets one pixel inside {@code bounds} to {@code argb} as part of the stroke under way; one outside is left alone. */
    void set(int x, int y, int argb, Rectangle bounds) {
        if (this.stroke == null || !bounds.contains(x, y)) return;
        int before = this.sheet.getRGB(x, y);
        if (before == argb) return;
        this.stroke.putIfAbsent(y * this.sheet.getWidth() + x, before);
        write(x, y, before, argb);
    }

    /** Sets every pixel on the straight line between two pixels, both included, as part of the stroke under way. */
    void line(int fromX, int fromY, int toX, int toY, int argb, Rectangle bounds) {
        int dx = Math.abs(toX - fromX);
        int dy = -Math.abs(toY - fromY);
        int stepX = fromX < toX ? 1 : -1;
        int stepY = fromY < toY ? 1 : -1;
        int error = dx + dy;
        int x = fromX;
        int y = fromY;
        while (true) {
            set(x, y, argb, bounds);
            if (x == toX && y == toY) return;
            int doubled = 2 * error;
            if (doubled >= dy) {
                error += dy;
                x += stepX;
            }
            if (doubled <= dx) {
                error += dx;
                y += stepY;
            }
        }
    }

    /**
     * Sets the pixels of the same color as the one at {@code x}, {@code y} that touch it by an edge, inside
     * {@code bounds}, to {@code argb}, as part of the stroke under way.
     */
    void fill(int x, int y, int argb, Rectangle bounds) {
        if (!bounds.contains(x, y)) return;
        int target = this.sheet.getRGB(x, y);
        if (target == argb) return;
        Deque<int[]> open = new ArrayDeque<>();
        open.push(new int[]{x, y});
        while (!open.isEmpty()) {
            int[] pixel = open.pop();
            int px = pixel[0];
            int py = pixel[1];
            if (!bounds.contains(px, py) || this.sheet.getRGB(px, py) != target) continue;
            set(px, py, argb, bounds);
            open.push(new int[]{px + 1, py});
            open.push(new int[]{px - 1, py});
            open.push(new int[]{px, py + 1});
            open.push(new int[]{px, py - 1});
        }
    }

    /** Ends the stroke under way; returns whether it changed a pixel. */
    boolean end() {
        Map<Integer, Integer> changed = this.stroke;
        this.stroke = null;
        if (changed == null || changed.isEmpty()) return false;
        int[] pixels = new int[changed.size()];
        int[] before = new int[changed.size()];
        int[] after = new int[changed.size()];
        int position = 0;
        for (Map.Entry<Integer, Integer> entry : changed.entrySet()) {
            int index = entry.getKey();
            pixels[position] = index;
            before[position] = entry.getValue();
            after[position] = this.sheet.getRGB(index % this.sheet.getWidth(), index / this.sheet.getWidth());
            position++;
        }
        this.done.push(new Stroke(pixels, before, after));
        this.undone.clear();
        return true;
    }

    boolean canUndo() {
        return this.stroke == null && !this.done.isEmpty();
    }

    boolean canRedo() {
        return this.stroke == null && !this.undone.isEmpty();
    }

    /** Takes back the last stroke; returns whether there was one. A stroke under way is finished first. */
    boolean undo() {
        if (!canUndo()) return false;
        Stroke stroke = this.done.pop();
        paint(stroke.pixels(), stroke.before());
        this.undone.push(stroke);
        return true;
    }

    /** Makes the last stroke taken back again; returns whether there was one. A stroke under way is finished first. */
    boolean redo() {
        if (!canRedo()) return false;
        Stroke stroke = this.undone.pop();
        paint(stroke.pixels(), stroke.after());
        this.done.push(stroke);
        return true;
    }

    private void paint(int[] pixels, int[] colors) {
        int width = this.sheet.getWidth();
        for (int position = 0; position < pixels.length; position++) {
            int x = pixels[position] % width;
            int y = pixels[position] / width;
            write(x, y, this.sheet.getRGB(x, y), colors[position]);
        }
    }

    private void write(int x, int y, int before, int after) {
        this.sheet.setRGB(x, y, after);
        count(before, -1);
        count(after, 1);
    }

    private void count(int argb, int change) {
        if (argb >>> 24 != 0) this.counts.merge(argb, change, (count, added) -> count + added == 0 ? null : count + added);
    }

    /** The colors of the sheet that are not fully transparent, the most used first, at most {@code limit}. */
    List<Integer> palette(int limit) {
        return this.counts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Whether two images have the same size and pixels; null is the same only as null. */
    static boolean same(BufferedImage first, BufferedImage second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        if (first.getWidth() != second.getWidth() || first.getHeight() != second.getHeight()) return false;
        int width = first.getWidth();
        int[] firstRow = new int[width];
        int[] secondRow = new int[width];
        for (int y = 0; y < first.getHeight(); y++) {
            first.getRGB(0, y, width, 1, firstRow, 0, width);
            second.getRGB(0, y, width, 1, secondRow, 0, width);
            if (!Arrays.equals(firstRow, secondRow)) return false;
        }
        return true;
    }

    /**
     * A copy of {@code image} with 8-bit red, green, blue and alpha, which every color can be set in. A gray image keeps its
     * stored values, as the game reads them: Java would take them for linear light and brighten them.
     */
    static BufferedImage copy(BufferedImage image) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int width = image.getWidth();
        int[] row = new int[width];
        ColorModel model = image.getColorModel();
        boolean gray = model.getColorSpace().getType() == ColorSpace.TYPE_GRAY;
        Raster raster = image.getRaster();
        for (int y = 0; y < image.getHeight(); y++) {
            if (gray) {
                for (int x = 0; x < width; x++) {
                    int value = eightBits(raster.getSample(x, y, 0), model.getComponentSize(0));
                    int alpha = model.hasAlpha() ? eightBits(raster.getSample(x, y, 1), model.getComponentSize(1)) : 0xFF;
                    row[x] = alpha << 24 | value << 16 | value << 8 | value;
                }
            } else {
                image.getRGB(0, y, width, 1, row, 0, width);
            }
            copy.setRGB(0, y, width, 1, row, 0, width);
        }
        return copy;
    }

    /** A sample of {@code bits} bits scaled to eight. */
    private static int eightBits(int sample, int bits) {
        return bits == 8 ? sample : Math.round(sample * 255f / ((1 << bits) - 1));
    }

    /** The image as a PNG with red, green, blue and alpha, which the game reads whatever the file held before. */
    static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("No PNG writer is available");
        return bytes.toByteArray();
    }
}
