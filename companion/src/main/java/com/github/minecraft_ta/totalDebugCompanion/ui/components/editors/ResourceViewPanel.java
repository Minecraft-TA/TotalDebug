package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.resource.ContentSource;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.resource.ResourceFileType;
import com.github.minecraft_ta.totalDebugCompanion.resource.ResourceLoader;
import java.util.function.Consumer;
import java.beans.PropertyChangeListener;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ResourceViewPanel extends JPanel {

    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Resource viewer loader");
        thread.setDaemon(true);
        return thread;
    });

    private final NavigationService navigation;
    private final ContentSource source;
    private final ResourceFileType fileType;
    private String metadata = "";

    private CompletableFuture<LoadedResource> loadTask;
    private Component activeView;
    private boolean disposed;

    public ResourceViewPanel(ContentSource source, ResourceFileType fileType, NavigationService navigation) {
        super(new BorderLayout());
        this.navigation = navigation;
        this.source = source;
        this.fileType = fileType;
        reload();
    }

    public void reload() {
        if (this.disposed) {
            return;
        }
        if (this.loadTask != null) {
            this.loadTask.cancel(true);
        }
        showCenteredMessage("Loading " + this.source.displayName() + "...", null);
        setMetadata("");
        CompletableFuture<LoadedResource> task = CompletableFuture.supplyAsync(() -> {
            try {
                return ResourceLoader.load(this.source, this.fileType);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, LOADER);
        this.loadTask = task;
        task.whenComplete((content, failure) -> SwingUtilities.invokeLater(() -> {
            if (this.disposed || this.loadTask != task) {
                if (content instanceof LoadedResource.Image image) {
                    image.value().flush();
                }
                return;
            }
            if (failure != null) {
                Throwable cause = unwrap(failure);
                showCenteredMessage(messageFor(cause), this::reload);
                return;
            }
            showContent(content);
        }));
    }

    private void showContent(LoadedResource content) {
        Component view = switch (content) {
            case LoadedResource.Text text -> new TextFileViewPanel(text, this.fileType, this::setMetadata);
            case LoadedResource.Image image -> new ImageViewPanel(image, this::setMetadata);
        };
        if (view instanceof AbstractTextViewPanel text) text.installNavigationHistoryMenu(navigation);
        replaceActiveView(view);
    }

    private void showCenteredMessage(String message, Runnable retry) {
        JPanel panel = new JPanel(new GridBagLayout());
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        JLabel label = new JLabel(message);
        label.putClientProperty("html.disable", Boolean.TRUE);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(label);
        if (retry != null) {
            content.add(Box.createVerticalStrut(10));
            JButton button = new JButton("Retry");
            button.setAlignmentX(Component.CENTER_ALIGNMENT);
            button.addActionListener(event -> retry.run());
            content.add(button);
        }
        panel.add(content);
        replaceActiveView(panel);
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
        return this.activeView instanceof AbstractTextViewPanel textView
                ? textView.requestFocusInWindow() : super.requestFocusInWindow();
    }

    @Override
    public boolean requestFocusInWindow(FocusEvent.Cause cause) {
        return this.activeView instanceof AbstractTextViewPanel textView
                ? textView.requestFocusInWindow(cause) : super.requestFocusInWindow(cause);
    }

    private void disposeActiveView() {
        if (this.activeView instanceof AbstractTextViewPanel textView) {
            textView.dispose();
        } else if (this.activeView instanceof ImageViewPanel imageView) {
            imageView.dispose();
        }
        this.activeView = null;
    }

    private static String messageFor(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "Unable to open this file" : message;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public void dispose() {
        if (this.disposed) {
            return;
        }
        this.disposed = true;
        if (this.loadTask != null) {
            this.loadTask.cancel(true);
        }
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
