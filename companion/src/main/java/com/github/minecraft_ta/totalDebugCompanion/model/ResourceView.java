package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.resource.ContentSource;
import com.github.minecraft_ta.totalDebugCompanion.resource.ArchiveEntrySource;
import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.resource.LocalFileSource;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.resource.ResourceFileType;
import com.github.minecraft_ta.totalDebugCompanion.resource.ResourceLoader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ResourceViewPanel;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationViewState;

import javax.swing.Icon;
import java.awt.Component;
import java.util.Objects;

public final class ResourceView implements IEditorPanel {
    private final RuntimeBinding runtimeBinding;
    @Override public RuntimeBinding runtimeBinding() { return runtimeBinding; }


    private final EditorContext context;
    private final ContentSource source;
    private final ResourceFileType fileType;
    private final ResourceViewPanel panel;

    /** Reads {@code source} to open it, on file work. */
    public static ResourceViewPanel.Opened read(EditorContext context, ContentSource source) throws IOException {
        return ResourceViewPanel.read(source, FileTypeResolver.resolve(source.displayName()), context.project().resources());
    }

    /** Shows what {@link #read} read; it reads nothing itself. */
    public ResourceView(EditorContext context, ContentSource source, RuntimeBinding runtimeBinding, ResourceViewPanel.Opened opened) {
        this.runtimeBinding = runtimeBinding;
        this.context = context;
        this.source = Objects.requireNonNull(source, "source");
        this.fileType = FileTypeResolver.resolve(source.displayName());
        this.panel = new ResourceViewPanel(source, this.fileType, context.navigation(), context.project().resources(), opened);
    }

    /** Whether the tab shows text read-only, which a navigation to an offset reads again first ({@link #readText}). */
    public boolean showsReadOnlyText() {
        return this.panel.showsReadOnlyText();
    }

    /** Reads the text of {@code source} again, on file work, as before a navigation places an offset in it. */
    public static LoadedResource.Text readText(ContentSource source) throws IOException {
        LoadedResource content = ResourceLoader.load(source, FileTypeResolver.resolve(source.displayName()));
        if (content instanceof LoadedResource.Image image) {
            image.value().flush();
            throw new IOException(source.displayName() + " no longer holds text");
        }
        return (LoadedResource.Text) content;
    }

    /** Shows {@code text}, read again by {@link #readText}, in place of the text shown. */
    public void replaceText(LoadedResource.Text text) {
        this.panel.replaceText(text);
    }

    /** Places the caret at {@code offset} in the text shown, if {@code stillWanted} still holds then. */
    public CompletableFuture<Void> navigateToOffset(int offset, BooleanSupplier stillWanted) {
        return this.panel.navigateToOffset(offset, stillWanted);
    }

    public ContentSource source() {
        return this.source;
    }

    @Override
    public String getTitle() {
        return this.source.displayName();
    }

    @Override
    public String getTooltip() {
        return getLocation().tooltip();
    }

    @Override
    public Icon getIcon() {
        return this.fileType.icon();
    }

    @Override
    public Component getComponent() {
        return this.panel;
    }

    @Override
    public EditorLocation getLocation() {
        if (this.source instanceof ArchiveEntrySource archiveEntry) {
            return EditorLocation.forArchiveEntry(archiveEntry.archivePath(), archiveEntry.entryName());
        }
        if (this.source instanceof LocalFileSource localFile) {
            return EditorLocation.forFile(localFile.path(), context.project().profile().workspaceDirectory());
        }
        return new EditorLocation(this.source.displayName(), List.of(), this.source.tooltip());
    }

    @Override
    public boolean canClose() {
        return this.panel.canClose();
    }

    @Override
    public Runnable subscribeMetadata(Consumer<String> listener) {
        return this.panel.subscribeMetadata(listener);
    }

    @Override
    public NavigationTarget getNavigationTarget() {
        return switch (this.source) {
            case ArchiveEntrySource archive -> new NavigationTarget.ArchiveEntry(
                    archive.archivePath(),
                    archive.entryName()
            );
            case LocalFileSource file -> new NavigationTarget.LocalFile(file.path());
            default -> null;
        };
    }

    @Override
    public NavigationViewState captureNavigationViewState() {
        return this.panel.captureNavigationViewState();
    }

    @Override
    public void restoreNavigationViewState(NavigationViewState state) {
        this.panel.restoreNavigationViewState(state);
    }

    @Override
    public void dispose() {
        this.panel.dispose();
    }
}
