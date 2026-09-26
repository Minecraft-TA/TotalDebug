package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.awt.event.ActionEvent;
import java.util.Objects;

public class EditorTabs extends JTabbedPane {

    private final ASTCache astCache = new ASTCache();

    private final ExecutorService analysisExecutor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
                var thread = new Thread(runnable, "Java editor analysis");
                thread.setDaemon(true);
                return thread;
            });

    public ExecutorService analysisExecutor() { return analysisExecutor; }

    public ASTCache astCache() { return astCache; }

    private final List<IEditorPanel> editors = new ArrayList<>();
    private final List<Consumer<IEditorPanel>> selectedEditorListeners = new ArrayList<>();
    private Function<IEditorPanel, Action> revealActionProvider;

    public EditorTabs() {
        super();
        setTabLayoutPolicy(SCROLL_TAB_LAYOUT);
        setBorder(BorderFactory.createEmptyBorder());
        SpeedSearch.install(this, this::getTitleAt);
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "focusEditor");
        getActionMap().put("focusEditor", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                Component selected = getSelectedComponent();
                if (selected != null) {
                    selected.requestFocusInWindow();
                }
            }
        });
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("shift F10"), "tabMenu");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("CONTEXT_MENU"), "tabMenu");
        getActionMap().put("tabMenu", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                int index = getSelectedIndex();
                if (index < 0) return;
                Rectangle bounds = getBoundsAt(index);
                createContextMenu(index).show(EditorTabs.this, bounds.x, bounds.y + bounds.height);
            }
        });
        addChangeListener(event -> {
            refreshTabHeaders();
            notifySelectedEditorChanged();
        });
    }

    /**
     * Allow closing with middle mouse button.
     * <p>
     * We do this here to prevent selecting the tab when closing it. {@link #addMouseListener(MouseListener)} gets
     * called too late
     */
    @Override
    protected void processMouseEvent(MouseEvent e) {
        if (handlePopup(e, indexAtLocation(e.getX(), e.getY()))) return;
        if (!SwingUtilities.isMiddleMouseButton(e) || e.getID() != MouseEvent.MOUSE_PRESSED) {
            super.processMouseEvent(e);
            return;
        }

        int tabIndex = indexAtLocation(e.getX(), e.getY());
        if (tabIndex == -1) {
            super.processMouseEvent(e);
            return;
        }

        removeTabAt(tabIndex);
    }

    boolean handlePopup(MouseEvent event, int index) {
        if (!event.isPopupTrigger() && !SwingUtilities.isRightMouseButton(event)) return false;
        if (event.isPopupTrigger() && index >= 0) {
            createContextMenu(index).show(event.getComponent(), event.getX(), event.getY());
        }
        event.consume();
        return true;
    }

    public void setRevealActionProvider(Function<IEditorPanel, Action> provider) {
        this.revealActionProvider = Objects.requireNonNull(provider, "provider");
    }

    JPopupMenu createContextMenu(int index) {
        IEditorPanel editor = this.editors.get(index);
        JPopupMenu menu = new JPopupMenu();
        menu.add("Close").addActionListener(event -> closeMatching(candidate -> candidate == editor));
        JMenuItem others = menu.add("Close others");
        others.setEnabled(getTabCount() > 1);
        others.addActionListener(event -> closeMatching(candidate -> candidate != editor));
        menu.add("Close all").addActionListener(event -> closeMatching(candidate -> true));
        String location = editor.getLocation().location();
        Action reveal = this.revealActionProvider == null ? null : this.revealActionProvider.apply(editor);
        if (!location.isBlank() || reveal != null) menu.addSeparator();
        if (!location.isBlank()) {
            menu.add("Copy location").addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(location), null));
        }
        if (reveal != null) menu.add(reveal);
        return menu;
    }

    public boolean canCloseAll() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Editor close checks must run on the EDT");
        }
        return List.copyOf(this.editors).stream().allMatch(editor -> !editors.contains(editor) || editor.canClose());
    }

    public void closeMatching(Predicate<IEditorPanel> predicate) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Editor tabs must be closed on the EDT");
        }
        var matching = this.editors.stream().filter(predicate).toList();
        for (int i = matching.size() - 1; i >= 0; i--) {
            int index = editors.indexOf(matching.get(i));
            if (index >= 0) removeTabAt(index);
        }
    }

    @Override
    public void removeTabAt(int index) {
        IEditorPanel editor = this.editors.get(index);
        if (!editor.canClose())
            return;
        int currentIndex = editors.indexOf(editor);
        if (currentIndex < 0) return;
        editors.remove(currentIndex);
        super.removeTabAt(currentIndex);
        refreshTabHeaders();
        editor.dispose();
        notifySelectedEditorChanged();
    }

    public CompletableFuture<Void> openEditorTab(IEditorPanel editorPanel) {
        var future = new CompletableFuture<Void>();
        Runnable open = () -> {
            editors.add(editorPanel);
            Component component = editorPanel.getComponent();
            addTab(editorPanel.getTitle(), component);
            int index = indexOfComponent(component);
            setToolTipTextAt(index, editorPanel.getTooltip());
            EditorTabHeader header = new EditorTabHeader(this, editorPanel.getIcon());
            setTabComponentAt(index, header);
            setSelectedIndex(index);
            header.refreshState();
            focusEditor(component);

            future.complete(null);
        };
        UIUtils.onEdt(open);

        return future;
    }

    public <T extends IEditorPanel> CompletableFuture<T> focusOrCreateIfAbsent(Class<T> clazz, Predicate<T> filter, Supplier<T> supplier) {
        for (IEditorPanel editor : editors) {
            if (clazz.isAssignableFrom(editor.getClass()) && filter.test((T) editor)) {
                setSelectedIndex(this.editors.indexOf(editor));
                focusEditor(editor.getComponent());
                T matchingEditor = (T) editor;
                return matchingEditor.ready().thenApply(ignored -> matchingEditor);
            }
        }

        var tab = supplier.get();
        return openEditorTab(tab)
                .thenCompose(ignored -> tab.ready())
                .thenApply(ignored -> tab);
    }

    public CompletableFuture<Void> replacePreview(IEditorPanel previous, IEditorPanel replacement) {
        int index = editors.indexOf(previous);
        if (index < 0) { replacement.dispose(); return CompletableFuture.completedFuture(null); }
        var state = previous.captureNavigationViewState();
        editors.set(index, replacement);
        setComponentAt(index, replacement.getComponent());
        setTabComponentAt(index, new EditorTabHeader(this, replacement.getIcon()));
        previous.dispose();
        refreshEditorTitles();
        return replacement.ready().thenRunAsync(() -> replacement.restoreNavigationViewState(state), SwingUtilities::invokeLater);
    }

    public List<IEditorPanel> editors() { return List.copyOf(editors); }

    public void refreshEditorTitles() {
        for (int i = 0; i < editors.size(); i++) {
            setTitleAt(i, editors.get(i).getTitle());
            setToolTipTextAt(i, editors.get(i).getTooltip());
        }
        refreshTabHeaders();
        notifySelectedEditorChanged();
    }

    public IEditorPanel getSelectedEditor() {
        if (getSelectedIndex() == -1)
            return null;

        return editors.get(getSelectedIndex());
    }

    private void focusEditor(Component component) {
        SwingUtilities.invokeLater(() -> {
            if (getSelectedComponent() == component) {
                component.requestFocusInWindow();
            }
        });
    }

    public void addSelectedEditorListener(Consumer<IEditorPanel> listener) {
        this.selectedEditorListeners.add(listener);
        listener.accept(getSelectedEditor());
    }

    private void notifySelectedEditorChanged() {
        IEditorPanel selected = getSelectedEditor();
        for (Consumer<IEditorPanel> listener : this.selectedEditorListeners) {
            listener.accept(selected);
        }
    }

    private void refreshTabHeaders() {
        for (int index = 0; index < getTabCount(); index++) {
            if (getTabComponentAt(index) instanceof EditorTabHeader header) {
                header.refreshState();
            }
        }
    }
}
