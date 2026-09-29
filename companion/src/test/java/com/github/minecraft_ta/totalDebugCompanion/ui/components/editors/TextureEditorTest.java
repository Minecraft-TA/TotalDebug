package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.pack.GamePacks;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEditsFixture;
import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureEditorTest {
    private static final String TEXTURE = "assets/testmod/textures/item/gear.png";
    private static final int GRAY = 0xFF808080;

    @TempDir Path directory;

    @Test
    void anErasedPixelIsSavedIntoTheWorkingPackAndLaterStrokesAreDiscarded() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = ResourceEditsFixture.edits(GameLocations.of(this.directory, false), record,
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run,
                InstanceState.inMemory());
        edits.packs().packStack(new PackStackPayload(34, 48, List.of(new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", "")),
                List.of()));
        BufferedImage gear = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) gear.setRGB(x, y, GRAY);

        TextureEditor[] editor = new TextureEditor[1];
        SwingUtilities.invokeAndWait(() -> {
            editor[0] = new TextureEditor(TEXTURE, "testmod.jar", null, new LoadedResource.Image(gear, 100), edits, ignored -> { });
            selectTool(editor[0], "Pencil");
            assertFalse(editor[0].view().painter().paints(),
                    "until the pack's copy is read, a stroke would be drawn on the mod's copy and saved over the pack's");
        });
        try {
            awaitOnSwing(() -> editor[0].targetBox().getItemCount() > 0);
            SwingUtilities.invokeAndWait(() -> {
                selectTool(editor[0], "Eraser");
                click(editor[0], new Point(1, 2), 0);
                assertEquals(0, editor[0].shown().pixels().getRGB(1, 2));
                assertTrue(editor[0].modified());
                editor[0].save();
            });
            Path saved = edits.pack(TEXTURE).resolve(TEXTURE);
            awaitOnSwing(() -> Files.isRegularFile(saved) && !editor[0].modified());
            BufferedImage written = ImageIO.read(saved.toFile());
            assertEquals(0, written.getRGB(1, 2));
            assertEquals(GRAY, written.getRGB(0, 0));
            assertEquals(1, record.changes().size(), "the save is in the change record");

            SwingUtilities.invokeAndWait(() -> {
                click(editor[0], new Point(0, 1), 0);
                click(editor[0], new Point(3, 1), InputEvent.SHIFT_DOWN_MASK);
                assertEquals(0, editor[0].shown().pixels().getRGB(2, 1), "Shift+click erases the line from the last pixel");
                editor[0].discard();
                assertEquals(GRAY, editor[0].shown().pixels().getRGB(2, 1), "Discard goes back to the saved copy");
                assertEquals(0, editor[0].shown().pixels().getRGB(1, 2));
                assertFalse(editor[0].modified());
            });
        } finally {
            SwingUtilities.invokeAndWait(editor[0]::dispose);
        }
    }

    @Test
    void thePencilStartsWithTheMostUsedColorAndTheColorPickerTakesAnother() throws Exception {
        ResourceEdits edits = ResourceEditsFixture.edits(GameLocations.of(this.directory, false), ChangeRecord.inMemory(),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run,
                InstanceState.inMemory());
        BufferedImage gear = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) gear.setRGB(x, y, GRAY);
        gear.setRGB(3, 3, 0xFFFF0000);

        TextureEditor[] editor = new TextureEditor[1];
        SwingUtilities.invokeAndWait(() -> editor[0] = new TextureEditor(TEXTURE, "testmod.jar", null,
                new LoadedResource.Image(gear, 100), edits, ignored -> { }));
        try {
            awaitOnSwing(() -> editor[0].targetBox().getItemCount() > 0);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(GRAY, editor[0].color(), "the pencil starts with the texture's most used color");
                selectTool(editor[0], "Pencil");
                click(editor[0], new Point(0, 1), 0);
                assertEquals(GRAY, editor[0].shown().pixels().getRGB(0, 1));
                click(editor[0], new Point(3, 3), InputEvent.ALT_DOWN_MASK);
                assertEquals(0xFFFF0000, editor[0].shown().pixels().getRGB(3, 3), "Alt+click picks instead of drawing");
                click(editor[0], new Point(0, 0), 0);
                assertEquals(0xFFFF0000, editor[0].shown().pixels().getRGB(0, 0), "the pencil draws with the picked color");
                selectTool(editor[0], "Undo");
                assertEquals(GRAY, editor[0].shown().pixels().getRGB(0, 0), "Undo takes the stroke back");

                // A stroke keeps the tool it started with, whatever is chosen while it is drawn.
                selectTool(editor[0], "Eraser");
                ImageViewPanel.Painter painter = editor[0].view().painter();
                painter.press(new Point(1, 1), editor[0].view().shownRegion(), press(editor[0], 0));
                selectTool(editor[0], "Pencil");
                painter.drag(new Point(2, 1), editor[0].view().shownRegion(), press(editor[0], 0));
                painter.release();
                assertEquals(List.of(0, 0), List.of(editor[0].shown().pixels().getRGB(1, 1), editor[0].shown().pixels().getRGB(2, 1)));
            });
        } finally {
            SwingUtilities.invokeAndWait(editor[0]::dispose);
        }
    }

    @Test
    void aWorkingPackCopyTooLargeToEditIsRefusedBeforeItIsDecoded() throws Exception {
        ResourceEdits edits = ResourceEditsFixture.edits(GameLocations.of(this.directory, false), ChangeRecord.inMemory(),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run,
                InstanceState.inMemory());
        Path copy = edits.pack(TEXTURE).resolve(TEXTURE);
        Files.createDirectories(copy.getParent());
        ImageIO.write(new BufferedImage(2049, 2048, BufferedImage.TYPE_INT_ARGB), "png", copy.toFile());

        TextureEditor[] editor = new TextureEditor[1];
        SwingUtilities.invokeAndWait(() -> editor[0] = new TextureEditor(TEXTURE, "testmod.jar", null,
                new LoadedResource.Image(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), 100), edits, ignored -> { }));
        try {
            awaitOnSwing(() -> editor[0].noticeText().contains("2049 x 2048"));
            SwingUtilities.invokeAndWait(() -> {
                selectTool(editor[0], "Pencil");
                assertFalse(editor[0].view().painter().paints(), "a copy that cannot be read is not drawn over");
            });
        } finally {
            SwingUtilities.invokeAndWait(editor[0]::dispose);
        }
    }

    @Test
    void thePacksCopyPlaysItsOwnAnimation() throws Exception {
        ResourceEdits edits = ResourceEditsFixture.edits(GameLocations.of(this.directory, false), ChangeRecord.inMemory(),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run,
                InstanceState.inMemory());
        Path copy = edits.pack(TEXTURE).resolve(TEXTURE);
        Files.createDirectories(copy.getParent());
        ImageIO.write(new BufferedImage(4, 8, BufferedImage.TYPE_INT_ARGB), "png", copy.toFile());
        Files.writeString(copy.resolveSibling("gear.png.mcmeta"), "{\"animation\":{\"frametime\":2}}");

        TextureEditor[] editor = new TextureEditor[1];
        SwingUtilities.invokeAndWait(() -> editor[0] = new TextureEditor(TEXTURE, "testmod.jar", null,
                new LoadedResource.Image(new BufferedImage(4, 8, BufferedImage.TYPE_INT_ARGB), 100), edits, ignored -> { }));
        try {
            awaitOnSwing(() -> editor[0].targetBox().getItemCount() > 0);
            SwingUtilities.invokeAndWait(() -> assertEquals(4, editor[0].view().shownRegion().height,
                    "the copy in the pack is animated, two frames of 4 x 4, although the opened file is not"));
        } finally {
            SwingUtilities.invokeAndWait(editor[0]::dispose);
        }
    }

    /** Clicks the toolbar button with the accessible name {@code name}. */
    private static void selectTool(TextureEditor editor, String name) {
        for (Component component : allComponents(editor.view())) {
            if (component instanceof AbstractButton button && name.equals(button.getAccessibleContext().getAccessibleName())) {
                button.doClick();
                return;
            }
        }
        throw new AssertionError("No button named " + name);
    }

    private static List<Component> allComponents(Container container) {
        List<Component> found = new ArrayList<>();
        for (Component component : container.getComponents()) {
            found.add(component);
            if (component instanceof Container child) found.addAll(allComponents(child));
        }
        return found;
    }

    /** Presses and releases the left button on a pixel of the sheet, as the image view hands it to the painter. */
    private static void click(TextureEditor editor, Point pixel, int modifiers) {
        ImageViewPanel.Painter painter = editor.view().painter();
        painter.press(pixel, editor.view().shownRegion(), press(editor, modifiers));
        painter.release();
    }

    private static MouseEvent press(TextureEditor editor, int modifiers) {
        return new MouseEvent(editor.view(), MouseEvent.MOUSE_PRESSED, 0,
                modifiers | InputEvent.BUTTON1_DOWN_MASK, 0, 0, 1, false, MouseEvent.BUTTON1);
    }

    private static void awaitOnSwing(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean[] met = new boolean[1];
        while (true) {
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) return;
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting on the Swing thread");
            Thread.sleep(20);
        }
    }
}
