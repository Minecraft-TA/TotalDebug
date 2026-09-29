package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.TextureAnimation;
import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A texture of the pack, edited pixel by pixel (see {@link PackResourceEditor} for how it is saved). The shown frame of
 * an animation is drawn on, or the whole sheet. Tools: Pencil (B), Eraser (E), Fill (G) and Color Picker (I); with none
 * chosen (Escape) a drag moves the view, as the middle button always does. Alt+click with the pencil picks the color
 * under it, Shift+click draws a line from the last pixel drawn. Ctrl+Z undoes a stroke, Ctrl+Shift+Z or Ctrl+Y redoes
 * it. Open in External Editor saves the texture into its pack, opens that file in the image editor chosen in Settings,
 * and takes each save made there as a change, used by the game at once.
 */
final class TextureEditor extends PackResourceEditor<TextureEditor.Texture> {
    /**
     * A copy of the texture: its pixels, and the animation its {@code .mcmeta} declares, or null, with why that file could
     * not be used, or empty. Only the pixels are edited and compared.
     */
    record Texture(BufferedImage pixels, TextureAnimation animation, String animationProblem) {
    }

    /** How many of the texture's colors the palette offers, the most used in the whole sheet first. */
    private static final int PALETTE_SIZE = 16;
    /** The most pixels a texture has that is edited; a larger one, far above what textures use, is shown only. */
    private static final int EDITABLE_PIXELS = 2048 * 2048;
    /**
     * The content when the pack holds no copy and the opened file cannot supply one. It differs from every image, even a
     * transparent one: whatever is shown then is unsaved.
     */
    private static final Texture NONE = new Texture(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), null, "");

    private enum Tool {
        PENCIL, ERASER, FILL, PICKER
    }

    private final TexturePixels pixels;
    private final ImageViewPanel view;
    private final ButtonGroup tools = new ButtonGroup();
    private final FlatIconButton pencil = new FlatIconButton(Icons.PENCIL, true);
    private final FlatIconButton eraser = new FlatIconButton(Icons.ERASER, true);
    private final FlatIconButton fill = new FlatIconButton(Icons.FILL, true);
    private final FlatIconButton picker = new FlatIconButton(Icons.COLOR_PICKER, true);
    private final FlatIconButton undo = new FlatIconButton(Icons.UNDO, false);
    private final FlatIconButton redo = new FlatIconButton(Icons.REDO, false);
    private final FlatIconButton external = new FlatIconButton(Icons.EXTERNAL_EDITOR, false);
    /** Stops showing what became of the image editor's saves, or does nothing before it opened the texture. */
    private Runnable stopExternal = () -> { };
    /** The pack whose file the image editor has open, or null. */
    private Path externalPack;
    private final JButton color = new FlatIconButton(null, false);
    private final JPanel palette = new JPanel(new FlowLayout(FlowLayout.LEFT, 1, 0));
    /** The color drawn with, as ARGB. */
    private int argb;
    /** Whether the color was chosen, rather than taken from the texture's most used one. */
    private boolean colorChosen;
    /** Whether the pixels can be drawn on: the pack's copy was read. */
    private boolean editable;
    /** The last pixel drawn, where Shift+click starts a line, or null. */
    private Point last;
    /** The texture's animation file as it was read beside the opened texture, or null without one. */
    private final byte[] metadata;
    /** The content last loaded or saved, which the shown pixels are compared with. */
    private Texture saved;
    /** The animation the shown copy plays, with why its file could not be used, or empty. */
    private TextureAnimation animation;
    private String animationProblem;

    /** {@code metadata} receives the status line: size, zoom and the pixel under the mouse. */
    TextureEditor(String path, String origin, Path pack, LoadedResource.Image content, ResourceEdits edits,
                  Consumer<String> metadata) {
        // Gray textures are read as the game reads them, once, so every copy compares alike.
        this(path, origin, pack, content, new Texture(TexturePixels.copy(content.value()), content.animation(),
                content.animationProblem()), edits, metadata);
    }

    private TextureEditor(String path, String origin, Path pack, LoadedResource.Image content, Texture opened,
                          ResourceEdits edits, Consumer<String> metadata) {
        super(path, origin, pack, opened, edits);
        this.metadata = content.metadata();
        this.pixels = new TexturePixels(opened.pixels());
        this.saved = opened;
        this.animation = opened.animation();
        this.animationProblem = opened.animationProblem();
        this.view = new ImageViewPanel(new LoadedResource.Image(this.pixels.sheet(), content.byteCount(), content.animation(),
                content.animationProblem()), metadata);
        this.view.addTools(tool(this.pencil, Tool.PENCIL, "Pencil", "B"), tool(this.eraser, Tool.ERASER, "Eraser", "E"),
                tool(this.fill, Tool.FILL, "Fill", "G"), tool(this.picker, Tool.PICKER, "Color Picker", "I"),
                Box.createHorizontalStrut(10), this.color, this.palette, Box.createHorizontalStrut(10),
                action(this.undo, "Undo", "Ctrl+Z", this::undo),
                action(this.redo, "Redo", "Ctrl+Shift+Z", this::redo), Box.createHorizontalStrut(10),
                action(this.external, "Open in External Editor", null, this::openExternally));
        this.color.addActionListener(event -> chooseColor());
        this.view.setPainter(new Painter());
        takeMostUsedColor();
        bindKeys();
        refresh();
        start(this.view);
    }

    /** Whether an image is small enough to edit here. */
    static boolean editable(BufferedImage image) {
        return editable(image.getWidth(), image.getHeight());
    }

    private static boolean editable(int width, int height) {
        return (long) width * height <= EDITABLE_PIXELS;
    }

    /** Draws with the texture's most used color, until a color is chosen. */
    private void takeMostUsedColor() {
        List<Integer> colors = this.pixels.palette(1);
        setColor(colors.isEmpty() ? 0xFF000000 : colors.getFirst());
    }

    /** The image view, for tests. */
    ImageViewPanel view() {
        return this.view;
    }

    /** Gives the image the keyboard focus, where the tool keys work. */
    boolean focusImage() {
        return this.view.focusImage();
    }

    private Component tool(FlatIconButton button, Tool tool, String name, String key) {
        button.putClientProperty(Tool.class, tool);
        button.setToolTipText(Tooltip.action(name, key).html());
        button.getAccessibleContext().setAccessibleName(name);
        button.addActionListener(event -> this.view.updateCursor());
        this.tools.add(button);
        return button;
    }

    private Component action(FlatIconButton button, String name, String keys, Runnable action) {
        button.setToolTipText(Tooltip.action(name, keys).html());
        button.getAccessibleContext().setAccessibleName(name);
        button.addActionListener(event -> action.run());
        return button;
    }

    /** The tool chosen, or null to move the view. */
    private Tool tool() {
        for (AbstractButton button : List.of(this.pencil, this.eraser, this.fill, this.picker)) {
            if (button.isSelected()) return (Tool) button.getClientProperty(Tool.class);
        }
        return null;
    }

    private void choose(AbstractButton button) {
        if (button == null) this.tools.clearSelection();
        else button.setSelected(true);
        this.view.updateCursor();
    }

    private void bindKeys() {
        // Single keys choose tools only while the image has the focus, so they never reach a text field.
        InputMap viewKeys = this.view.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        bind(this.view, viewKeys, "B", "pencil", () -> choose(this.pencil));
        bind(this.view, viewKeys, "E", "eraser", () -> choose(this.eraser));
        bind(this.view, viewKeys, "G", "fill", () -> choose(this.fill));
        bind(this.view, viewKeys, "I", "picker", () -> choose(this.picker));
        bind(this.view, viewKeys, "ESCAPE", "move", () -> choose(null));
        InputMap keys = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        bind(this, keys, "ctrl Z", "undo", this::undo);
        bind(this, keys, "ctrl shift Z", "redo", this::redo);
        bind(this, keys, "ctrl Y", "redo", this::redo);
        bind(this, keys, "ctrl S", "save", this::save);
    }

    private static void bind(JComponent component, InputMap keys, String key, String name, Runnable action) {
        keys.put(KeyStroke.getKeyStroke(key), name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    /** Takes back the last stroke; a stroke being drawn is finished first. */
    private void undo() {
        // While another pack's copy is read, an undo would be kept over that copy.
        if (this.editable && this.pixels.undo()) edited();
    }

    /** Draws the last stroke taken back again; a stroke being drawn is finished first. */
    private void redo() {
        if (this.editable && this.pixels.redo()) edited();
    }

    /** Shows the pixels after a stroke, undo or redo changed them. */
    private void edited() {
        this.view.pixelsChanged();
        refresh();
        changed();
    }

    /** Shows what can be undone and the colors of the shown pixels. */
    private void refresh() {
        this.external.setEnabled(this.editable);
        this.undo.setEnabled(this.editable && this.pixels.canUndo());
        this.redo.setEnabled(this.editable && this.pixels.canRedo());
        this.palette.removeAll();
        for (int swatch : this.pixels.palette(PALETTE_SIZE)) {
            FlatIconButton button = new FlatIconButton(new Swatch(swatch, 10), false);
            button.setToolTipText(Tooltip.action("Use Color", null).text(ImageViewPanel.describeColor(swatch)).html());
            button.getAccessibleContext().setAccessibleName("Use Color");
            button.getAccessibleContext().setAccessibleDescription(ImageViewPanel.describeColor(swatch));
            button.addActionListener(event -> choose(swatch));
            this.palette.add(button);
        }
        this.palette.revalidate();
        this.palette.repaint();
    }

    private void chooseColor() {
        Color chosen = JColorChooser.showDialog(this, "Color", new Color(this.argb, true), true);
        if (chosen != null) choose(chosen.getRGB());
    }

    /** Draws with {@code argb} from now on, as the player chose. */
    private void choose(int argb) {
        this.colorChosen = true;
        setColor(argb);
    }

    private void setColor(int argb) {
        this.argb = argb;
        this.color.setIcon(new Swatch(argb, 16));
        this.color.setToolTipText(Tooltip.action("Choose Color", null).text("Draws with " + ImageViewPanel.describeColor(argb)).html());
        this.color.getAccessibleContext().setAccessibleName("Choose Color");
        this.color.getAccessibleContext().setAccessibleDescription(ImageViewPanel.describeColor(argb));
    }

    /** The color drawn with, as ARGB, for tests. */
    int color() {
        return this.argb;
    }

    /** Draws with the chosen tool in pixels of the sheet, one stroke per press; a stroke keeps the tool it started with. */
    private final class Painter implements ImageViewPanel.Painter {
        /** The tool of the press under way, or null between presses. */
        private Tool pressed;
        /** Whether the press under way picks colors rather than drawing. */
        private boolean picking;

        @Override
        public boolean paints() {
            return editable && tool() != null;
        }

        @Override
        public void press(Point pixel, Rectangle region, MouseEvent event) {
            Tool tool = tool();
            this.pressed = tool;
            this.picking = tool == Tool.PICKER || tool == Tool.PENCIL && event.isAltDown();
            if (this.picking) {
                pick(pixel, region);
                return;
            }
            pixels.begin();
            undo.setEnabled(false);
            redo.setEnabled(false);
            int drawn = tool == Tool.ERASER ? 0 : argb;
            if (tool == Tool.FILL) {
                pixels.fill(pixel.x, pixel.y, drawn, region);
            } else if (event.isShiftDown() && last != null && region.contains(last)) {
                // A line starts only at a pixel of the frame shown.
                pixels.line(last.x, last.y, pixel.x, pixel.y, drawn, region);
            } else {
                pixels.set(pixel.x, pixel.y, drawn, region);
            }
            last = pixel;
            view.pixelsChanged();
        }

        @Override
        public void drag(Point pixel, Rectangle region, MouseEvent event) {
            Tool tool = this.pressed;
            if (this.picking) {
                pick(pixel, region);
                return;
            }
            if (tool == null || tool == Tool.FILL || last == null || last.equals(pixel)) return;
            pixels.line(last.x, last.y, pixel.x, pixel.y, tool == Tool.ERASER ? 0 : argb, region);
            last = pixel;
            view.pixelsChanged();
        }

        @Override
        public void release() {
            this.pressed = null;
            if (this.picking) return;
            if (pixels.end()) edited();
            else refresh();
        }

        private void pick(Point pixel, Rectangle region) {
            if (region.contains(pixel)) choose(pixels.color(pixel.x, pixel.y));
        }
    }

    /** A square of a color over a checkerboard, so a transparent one shows as such. */
    private record Swatch(int argb, int size) implements Icon {
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            int half = this.size / 2;
            graphics.setColor(Color.WHITE);
            graphics.fillRect(x, y, this.size, this.size);
            graphics.setColor(Color.LIGHT_GRAY);
            graphics.fillRect(x, y, half, half);
            graphics.fillRect(x + half, y + half, this.size - half, this.size - half);
            graphics.setColor(new Color(this.argb, true));
            graphics.fillRect(x, y, this.size, this.size);
            graphics.setColor(ThemeColors.separator());
            graphics.drawRect(x, y, this.size - 1, this.size - 1);
        }

        @Override
        public int getIconWidth() {
            return this.size;
        }

        @Override
        public int getIconHeight() {
            return this.size;
        }
    }

    /**
     * The opened texture's animation, which the game reads only from the pack that supplies the texture or one above it:
     * without a copy beside the saved texture, the game would show every frame at once.
     */
    @Override
    protected Map<String, byte[]> alongside() {
        return this.metadata == null ? Map.of() : Map.of(path() + ".mcmeta", this.metadata);
    }

    @Override
    protected Texture shown() {
        return new Texture(this.pixels.sheet(), this.animation, this.animationProblem);
    }

    @Override
    protected boolean same(Texture first, Texture second) {
        if (first == NONE || second == NONE) return first == second;
        return TexturePixels.same(first.pixels(), second.pixels());
    }

    @Override
    protected void load(Texture content) {
        this.saved = content;
        showAnimation(content);
        // The same pixels keep what can be undone.
        if (same(shown(), content)) return;
        // With no copy left, Discard leaves transparent pixels, as text is left empty.
        this.pixels.replace(content == NONE ? new BufferedImage(this.pixels.sheet().getWidth(), this.pixels.sheet().getHeight(),
                BufferedImage.TYPE_INT_ARGB) : content.pixels());
        this.last = null;
        this.view.setSheet(this.pixels.sheet());
        if (!this.colorChosen) takeMostUsedColor();
        refresh();
    }

    @Override
    protected void markSaved(Texture content) {
        this.saved = content;
        // Unsaved pixels stay, and play as the game plays the copy read now.
        showAnimation(content);
    }

    /** Plays the animation of {@code content}'s copy, or none; with no copy left the shown one stays. */
    private void showAnimation(Texture content) {
        if (content == NONE || Objects.equals(content.animation(), this.animation)
                && content.animationProblem().equals(this.animationProblem)) return;
        this.animation = content.animation();
        this.animationProblem = content.animationProblem();
        this.view.setAnimation(this.animation, this.animationProblem);
    }

    @Override
    protected boolean modified() {
        return !same(shown(), this.saved);
    }

    @Override
    protected Texture decode(byte[] bytes) throws IOException {
        return new Texture(pixels(bytes), null, "");
    }

    /**
     * {@code pack}'s copy with the animation the game plays for it: the {@code .mcmeta} of the highest enabled pack from
     * {@code pack} up that supplies one, or none.
     */
    @Override
    protected Texture decode(byte[] bytes, Path pack) throws IOException {
        BufferedImage image = pixels(bytes);
        try {
            // As when a texture opens: a file larger than an animation needs is not read.
            var metadata = edits().packs().metadata(path(), pack, 1024 * 1024);
            if (metadata.isEmpty()) return new Texture(image, null, "");
            TextureAnimation animation = TextureAnimation.read(metadata.get(), image.getWidth(), image.getHeight()).orElse(null);
            if (animation != null && animation.frames().stream().noneMatch(frame -> animation.contains(frame.index()))) {
                return new Texture(image, null, "Animation frames lie outside the texture");
            }
            return new Texture(image, animation, "");
        } catch (IOException | RuntimeException invalid) {
            return new Texture(image, null, "Invalid animation metadata: " + invalid.getMessage());
        }
    }

    /** The pixels of a pack's copy, refused unreadable or too large to edit before it is decoded. */
    private static BufferedImage pixels(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) throw new IOException("The copy in the pack is not a PNG image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (!editable(width, height)) {
                    throw new IOException("The copy in the pack is " + width + " x " + height + ", larger than the 2048 x 2048 edited here");
                }
                return TexturePixels.copy(reader.read(0));
            } finally {
                reader.dispose();
            }
        }
    }

    @Override
    protected byte[] encode(Texture content) throws IOException {
        return TexturePixels.png(content.pixels());
    }

    @Override
    protected Texture copy(Texture content) {
        return new Texture(TexturePixels.copy(content.pixels()), content.animation(), content.animationProblem());
    }

    @Override
    protected Texture none() {
        return NONE;
    }

    @Override
    protected void setEditable(boolean editable) {
        this.editable = editable;
        this.view.updateCursor();
        refresh();
    }

    @Override
    protected String noun() {
        return "texture";
    }

    /**
     * Saves the texture into its pack where the pack does not hold what is shown, then opens that file in the image
     * editor chosen in Settings, or the system's app for images.
     */
    private void openExternally() {
        saveThen(pack -> edits().external().openLater(path(), pack, GlobalConfig.getInstance().imageEditor())
                .whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
                    if (disposed()) return;
                    if (failure != null) {
                        showNotice("Not opened: " + message(failure), ThemeColors::error);
                        return;
                    }
                    if (pack.equals(this.externalPack)) return;
                    // The image editor's saves come as changes of the pack's copy, which this tab reads; what the game
                    // made of each is shown here.
                    this.stopExternal.run();
                    this.externalPack = pack;
                    this.stopExternal = edits().external().addListener(path(), pack, (saved, problem) -> SwingUtilities.invokeLater(() -> {
                        // A tab moved to another pack since shows that pack's state, not what became of this one's file.
                        if (disposed() || !pack.equals(currentPack())) return;
                        if (problem != null) {
                            showNotice("Not taken from the image editor: " + message(problem), ThemeColors::error);
                        } else if (!saved.reloadFailure().isEmpty() || !saved.problems().isEmpty() || !saved.unused().isEmpty()) {
                            // Only what went wrong: reading the new copy, such as one too large to edit here, says the rest.
                            showSaved(saved);
                        }
                    }));
                })));
    }

    @Override
    void dispose() {
        super.dispose();
        this.stopExternal.run();
        this.view.dispose();
    }
}
