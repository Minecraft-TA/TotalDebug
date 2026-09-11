package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.resource.ContentSource;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.resource.ResourceFileType;
import com.github.minecraft_ta.totalDebugCompanion.resource.ResourceLoader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.BottomInformationBar;

import javax.swing.*;
import java.awt.*;
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
    private final BottomInformationBar informationBar = new BottomInformationBar();

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
        this.informationBar.setProcessInfoText("Loading " + this.source.displayName());
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
                this.informationBar.setFailureInfoText(messageFor(cause));
                return;
            }
            showContent(content);
        }));
    }

    private void showContent(LoadedResource content) {
        Component view = switch (content) {
            case LoadedResource.Text text -> new TextFileViewPanel(text, this.fileType, this.informationBar);
            case LoadedResource.Image image -> new ImageViewPanel(image, this.informationBar);
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

    public BottomInformationBar getBottomInformationBar() {
        return this.informationBar;
    }
}
