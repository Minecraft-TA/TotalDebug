package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationViewState;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourcePaths;
import com.github.minecraft_ta.totalDebugCompanion.resource.ArchiveEntrySource;
import com.github.minecraft_ta.totalDebugCompanion.resource.ContentSource;
import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.resource.LocalFileSource;
import com.github.minecraft_ta.totalDebugCompanion.resource.ResourceFileType;
import com.github.minecraft_ta.totalDebugCompanion.resource.ResourceLoader;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A resource file as its tab shows it: read-only text or an image, or the pack's editor for a resource of a pack. It is
 * built with what {@link #read} read before the tab existed (docs/EDITOR_LOADING.md) and reads nothing itself; a pack
 * editor follows the pack's copies through its own loader.
 */
public final class ResourceViewPanel extends JPanel {

    /**
     * What opening a resource reads: its content, the folder pack a local file lies in, which its editor saves into, or
     * null, and its path inside a pack, which decides whether an editor edits it, or null.
     */
    public record Opened(LoadedResource content, Path pack, String resourcePath) {
    }

    private final NavigationService navigation;
    private final ContentSource source;
    private final ResourceFileType fileType;
    private final ResourceEdits edits;
    private final Opened opened;
    private String metadata = "";
    private Component activeView;

    /** The editor the panel shows, or null while it shows the resource read-only, for tests. */
    PackResourceEditor<?> editor() {
        return this.activeView instanceof PackResourceEditor<?> editor ? editor : null;
    }

    /**
     * Reads {@code source} to open it, on file work. {@code edits} writes text resources of the pack into the managed
     * pack, or is null where they stay read-only.
     */
    public static Opened read(ContentSource source, ResourceFileType fileType, ResourceEdits edits) throws IOException {
        LoadedResource content = ResourceLoader.load(source, fileType);
        try {
            Path pack = edits != null && source instanceof LocalFileSource file ? edits.packOf(file.path()).orElse(null) : null;
            String resourcePath = edits == null ? null : ResourcePaths.of(source).orElse(null);
            return new Opened(content, pack, resourcePath);
        } catch (RuntimeException failure) {
            if (content instanceof LoadedResource.Image image) image.value().flush();
            throw failure;
        }
    }

    /** Shows what {@link #read} read; {@code edits} as there. */
    public ResourceViewPanel(ContentSource source, ResourceFileType fileType, NavigationService navigation,
                             ResourceEdits edits, Opened opened) {
        super(new BorderLayout());
        this.edits = edits;
        this.navigation = navigation;
        this.source = source;
        this.fileType = fileType;
        this.opened = opened;
        Component view = switch (opened.content()) {
            case LoadedResource.Text text -> textView(text);
            case LoadedResource.Image image -> imageView(image);
        };
        if (view instanceof AbstractTextViewPanel text) text.installNavigationHistoryMenu(navigation);
        if (view instanceof ResourceTextEditor editor) editor.textPanel().installNavigationHistoryMenu(navigation);
        replaceActiveView(view);
    }

    /** Whether the panel shows text read-only, which a navigation to an offset reads again first. */
    public boolean showsReadOnlyText() {
        return this.activeView instanceof TextFileViewPanel;
    }

    /** Shows {@code text}, the file read again, in place of the read-only text shown, as before an offset is placed. */
    public void replaceText(LoadedResource.Text text) {
        if (!showsReadOnlyText()) throw new IllegalStateException("Only read-only text is read again");
        TextFileViewPanel view = new TextFileViewPanel(text, this.fileType, this::setMetadata);
        view.installNavigationHistoryMenu(this.navigation);
        replaceActiveView(view);
    }

    /**
     * Places the caret at {@code offset} in the text shown, if {@code stillWanted} still holds then; an image has no
     * offset, and completes at once.
     */
    public CompletableFuture<Void> navigateToOffset(int offset, BooleanSupplier stillWanted) {
        AbstractTextViewPanel text = textPanel();
        return text == null ? CompletableFuture.completedFuture(null) : text.navigateToOffset(offset, stillWanted);
    }

    /** The caret and scroll of the text shown; an image keeps none. */
    public NavigationViewState captureNavigationViewState() {
        AbstractTextViewPanel text = textPanel();
        return text == null ? NavigationViewState.EMPTY : text.captureNavigationViewState();
    }

    public void restoreNavigationViewState(NavigationViewState state) {
        AbstractTextViewPanel text = textPanel();
        if (text != null) text.restoreNavigationViewState(state);
    }

    /** The text the panel shows, read-only or in its editor, or null for an image. */
    private AbstractTextViewPanel textPanel() {
        if (this.activeView instanceof AbstractTextViewPanel text) return text;
        if (this.activeView instanceof ResourceTextEditor editor) return editor.textPanel();
        return null;
    }

    /** An editor for a text resource of the pack, otherwise the read-only text. */
    private Component textView(LoadedResource.Text text) {
        String path = this.opened.resourcePath();
        if (path == null) return new TextFileViewPanel(text, this.fileType, this::setMetadata);
        setMetadata(this.fileType.description());
        return new ResourceTextEditor(path, origin(), this.opened.pack(), text, this.edits);
    }

    /** The file an editor names as where the resource comes from, such as a mod's JAR. */
    private String origin() {
        return this.source instanceof ArchiveEntrySource entry
                ? entry.archivePath().getFileName().toString() : this.source.displayName();
    }

    /** An editor for a texture of the pack, otherwise the image. */
    private Component imageView(LoadedResource.Image image) {
        String path = this.opened.resourcePath();
        if (path == null || !ResourcePaths.editableImage(path) || !TextureEditor.editable(image.value())) {
            return new ImageViewPanel(image, this::setMetadata);
        }
        return new TextureEditor(path, origin(), this.opened.pack(), image, this.edits, this::setMetadata);
    }

    /** Whether the tab can close: an edited resource has no unsaved changes, or they were discarded after asking. */
    public boolean canClose() {
        return !(this.activeView instanceof PackResourceEditor<?> editor) || editor.confirmLeave();
    }

    private void replaceActiveView(Component replacement) {
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean hadFocus = focusOwner == this
                || (focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, this));
        disposeActiveView();
        removeAll();
        this.activeView = replacement;
        add(replacement, BorderLayout.CENTER);
        revalidate();
        repaint();
        if (hadFocus) {
            SwingUtilities.invokeLater(() -> {
                if (isShowing()) {
                    requestFocusInWindow();
                }
            });
        }
    }

    @Override
    public boolean requestFocusInWindow() {
        if (this.activeView instanceof ResourceTextEditor editor) return editor.textPanel().requestFocusInWindow();
        if (this.activeView instanceof TextureEditor editor && editor.focusImage()) return true;
        return this.activeView instanceof AbstractTextViewPanel textView
                ? textView.requestFocusInWindow() : super.requestFocusInWindow();
    }

    @Override
    public boolean requestFocusInWindow(FocusEvent.Cause cause) {
        if (this.activeView instanceof ResourceTextEditor editor) return editor.textPanel().requestFocusInWindow(cause);
        if (this.activeView instanceof TextureEditor editor && editor.focusImage()) return true;
        return this.activeView instanceof AbstractTextViewPanel textView
                ? textView.requestFocusInWindow(cause) : super.requestFocusInWindow(cause);
    }

    private void disposeActiveView() {
        if (this.activeView instanceof PackResourceEditor<?> editor) {
            editor.dispose();
        } else if (this.activeView instanceof AbstractTextViewPanel textView) {
            textView.dispose();
        } else if (this.activeView instanceof ImageViewPanel imageView) {
            imageView.dispose();
        }
        this.activeView = null;
    }

    public void dispose() {
        disposeActiveView();
    }

    private void setMetadata(String value) {
        String previous = metadata;
        metadata = value;
        firePropertyChange("metadata", previous, value);
    }

    public Runnable subscribeMetadata(Consumer<String> listener) {
        PropertyChangeListener changed = event -> listener.accept((String) event.getNewValue());
        addPropertyChangeListener("metadata", changed);
        listener.accept(metadata);
        return () -> removePropertyChangeListener("metadata", changed);
    }
}
