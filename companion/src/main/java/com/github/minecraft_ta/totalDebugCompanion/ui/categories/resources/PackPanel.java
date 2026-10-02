package com.github.minecraft_ta.totalDebugCompanion.ui.categories.resources;

import com.github.minecraft_ta.totalDebugCompanion.ui.categories.Explorer;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.PackFolders;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.NoticeLine;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PageLoader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PixelImages;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.PlateIcon;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.SubjectHeader;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A resource pack or datapack of its own folder or zip file: its icon and what its {@code pack.mcmeta} says, and every
 * file it holds, in the browser a mod's Resources tab uses. The pack is read again whenever the page is shown.
 */
public final class PackPanel extends JPanel {
    /** What was read of the pack: its {@code pack.mcmeta}, or null without one, its icon or null, and its files. */
    private record Loaded(PackFolders.Meta meta, BufferedImage icon, ResourceBrowser.Prepared resources) {
    }

    private final Path file;
    private final SubjectHeader header = new SubjectHeader();
    private final ResourceBrowser browser;
    /** Why Show in Explorer could not show the pack. */
    private final NoticeLine notice = new NoticeLine();
    private final PageLoader<Loaded> loader;

    public PackPanel(Path file, Consumer<NavigationTarget> navigator) {
        super(new BorderLayout());
        this.file = Objects.requireNonNull(file, "file");
        this.browser = new ResourceBrowser(Objects.requireNonNull(navigator, "navigator"));
        this.header.setTitle(PackFolders.title(file));
        this.header.setIcon(new PlateIcon(Icons.RESOURCES_ROOT, SubjectHeader.ICON_SIZE));
        JButton show = new JButton("Show in Explorer", Icons.FOLDER);
        show.addActionListener(event -> Explorer.show(file, this.notice::show));
        this.header.addControl(show);
        JPanel top = new JPanel(new BorderLayout());
        top.add(this.header, BorderLayout.NORTH);
        top.add(this.notice, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(this.browser, BorderLayout.CENTER);
        this.loader = new PageLoader<>(() -> () -> read(file), this::show, failure -> {
            // What was read before is not the pack any more.
            this.header.setIcon(new PlateIcon(Icons.RESOURCES_ROOT, SubjectHeader.ICON_SIZE));
            this.header.setSubtitle(List.of());
            this.browser.setResources(ResourceBrowser.Prepared.NONE);
            this.browser.setMessage(PackFolders.title(file) + " could not be read: " + failure.getMessage());
        }).page(this).readsWhenShown(this);
    }

    private static Loaded read(Path file) throws IOException {
        return new Loaded(PackFolders.meta(file).orElse(null), icon(file),
                ResourceBrowser.prepare(side(file, ModResources.list(file)), Map.of(), Map.of()));
    }

    /**
     * The files the game reads from the pack: assets from a resource pack, data from a world's datapack; a pack kept
     * elsewhere lists both.
     */
    private static List<ModResources.Resource> side(Path file, List<ModResources.Resource> resources) {
        Path parent = file.toAbsolutePath().normalize().getParent();
        String folder = parent == null || parent.getFileName() == null ? "" : parent.getFileName().toString();
        String root = folder.equals("resourcepacks") ? "assets/" : folder.equals("datapacks") ? "data/" : "";
        return root.isEmpty() ? resources : resources.stream().filter(resource -> resource.path().startsWith(root)).toList();
    }

    /** The pack's {@code pack.png} fitted into the header, or null without a readable one. */
    private static BufferedImage icon(Path file) {
        try {
            byte[] png = PackFolders.read(file, "pack.png");
            BufferedImage image = png == null ? null : ImageIO.read(new ByteArrayInputStream(png));
            return image == null ? null : PixelImages.fitWithin(image, SubjectHeader.ICON_SIZE, SubjectHeader.ICON_SIZE);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private void show(Loaded loaded) {
        this.header.setIcon(loaded.icon() != null ? new ImageIcon(loaded.icon()) : new PlateIcon(Icons.RESOURCES_ROOT, SubjectHeader.ICON_SIZE));
        List<JComponent> subtitle = new ArrayList<>();
        if (loaded.meta() != null && !loaded.meta().description().isBlank()) {
            subtitle.add(SubjectHeader.text(loaded.meta().description().strip()));
        }
        if (loaded.meta() != null && loaded.meta().format() > 0) {
            subtitle.add(SubjectHeader.text("Pack format " + NumberFormat.getIntegerInstance(Locale.ROOT).format(loaded.meta().format())));
        }
        this.header.setSubtitle(subtitle);
        this.browser.setResources(loaded.resources());
        this.browser.setMessage(loaded.resources().resources().isEmpty() ? "The pack holds no resources." : "");
    }

    public Path file() {
        return this.file;
    }

    public String title() {
        return PackFolders.title(this.file);
    }

    ResourceBrowser browser() {
        return this.browser;
    }

    public void dispose() {
        this.loader.dispose();
        this.browser.dispose();
    }
}
