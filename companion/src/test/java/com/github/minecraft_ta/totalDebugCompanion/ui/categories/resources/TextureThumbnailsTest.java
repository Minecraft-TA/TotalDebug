package com.github.minecraft_ta.totalDebugCompanion.ui.categories.resources;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A texture replaced at the same path shows its new thumbnail (docs/LAST_DIFFERENCES.md, part 2). */
class TextureThumbnailsTest {
    private static final String TEXTURE = "assets/testmod/textures/block/widget.png";

    @TempDir Path directory;

    @Test
    void aLooseTextureReplacedAtTheSamePathShowsItsNewImage() throws Exception {
        Path mod = this.directory.resolve("mod");
        Path file = mod.resolve(TEXTURE);
        Files.createDirectories(file.getParent());
        Files.write(file, png(Color.RED, 2));
        TextureThumbnails thumbnails = new TextureThumbnails(32);
        assertEquals(Color.RED.getRGB(), centre(thumbnail(thumbnails, texture(mod))));

        Files.write(file, png(Color.BLUE, 4));
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(60)));
        assertEquals(Color.BLUE.getRGB(), centre(thumbnail(thumbnails, texture(mod))), "the listing again shows the new image");
    }

    @Test
    void aTextureInAnArchiveReplacedAtTheSamePathShowsItsNewImage() throws Exception {
        Path jar = this.directory.resolve("mod.jar");
        archive(jar, png(Color.RED, 2));
        TextureThumbnails thumbnails = new TextureThumbnails(32);
        assertEquals(Color.RED.getRGB(), centre(thumbnail(thumbnails, texture(jar))));

        archive(jar, png(Color.BLUE, 4));
        Files.setLastModifiedTime(jar, FileTime.from(Instant.now().plusSeconds(60)));
        assertEquals(Color.BLUE.getRGB(), centre(thumbnail(thumbnails, texture(jar))), "the listing again shows the new image");
    }

    @Test
    void aRefreshedListingWithAReplacedTextureKeepsTheSelectedRow() throws Exception {
        Path mod = this.directory.resolve("mod");
        Path file = mod.resolve(TEXTURE);
        Files.createDirectories(file.getParent());
        Files.write(mod.resolve("assets/testmod/textures/block/another.png"), png(Color.GREEN, 2));
        Files.write(file, png(Color.RED, 2));
        ResourceBrowser.Prepared before = ResourceBrowser.prepare(ModResources.list(mod), Map.of(), Map.of());
        Files.write(file, png(Color.BLUE, 4));
        ResourceBrowser.Prepared after = ResourceBrowser.prepare(ModResources.list(mod), Map.of(), Map.of());

        String[] selected = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            ResourceBrowser browser = new ResourceBrowser(target -> { });
            browser.setResources(before);
            int row = indexOf(browser, TEXTURE);
            browser.resourceList().setSelectedIndex(row);
            browser.setResources(after);
            ModResources.Resource kept = browser.resourceList().getSelectedValue();
            selected[0] = kept == null ? null : kept.path();
        });
        assertEquals(TEXTURE, selected[0]);
    }

    private static int indexOf(ResourceBrowser browser, String path) {
        for (int row = 0; row < browser.rowCount(); row++) {
            if (browser.resourceList().getModel().getElementAt(row).path().equals(path)) return row;
        }
        throw new AssertionError(path + " is not listed");
    }

    private static ModResources.Resource texture(Path file) throws IOException {
        return ModResources.list(file).stream().filter(resource -> resource.path().equals(TEXTURE)).findFirst().orElseThrow();
    }

    /** The thumbnail of {@code texture}, once it loaded. */
    private static BufferedImage thumbnail(TextureThumbnails thumbnails, ModResources.Resource texture) throws Exception {
        JLabel painting = new JLabel();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            Icon[] icon = new Icon[1];
            SwingUtilities.invokeAndWait(() -> icon[0] = thumbnails.icon(texture, painting));
            if (icon[0] != null) return (BufferedImage) ((ImageIcon) icon[0]).getImage();
            Thread.sleep(20);
        }
        throw new AssertionError("The thumbnail did not load");
    }

    private static int centre(BufferedImage image) {
        assertNotNull(image);
        return image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
    }

    private static byte[] png(Color color, int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) image.setRGB(x, y, color.getRGB());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        return bytes.toByteArray();
    }

    private static void archive(Path jar, byte[] texture) throws IOException {
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry(TEXTURE));
            zip.write(texture);
            zip.closeEntry();
            // A second entry so the archive's size differs with the texture's.
            zip.putNextEntry(new ZipEntry("assets/testmod/lang/en_us.json"));
            zip.write("{}".getBytes());
            zip.closeEntry();
        }
        assertEquals(List.of(TEXTURE), ModResources.list(jar).stream().map(ModResources.Resource::path)
                .filter(TEXTURE::equals).toList());
    }
}
