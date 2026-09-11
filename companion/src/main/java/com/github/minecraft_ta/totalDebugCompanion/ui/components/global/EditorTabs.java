package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.Consumer;

public class EditorTabs extends JTabbedPane {

    private final ASTCache astCache = new ASTCache();

    public ASTCache astCache() { return astCache; }

    private final List<IEditorPanel> editors = new ArrayList<>();
    private final List<Consumer<IEditorPanel>> selectedEditorListeners = new ArrayList<>();

    public EditorTabs() {
        super();
        setTabLayoutPolicy(SCROLL_TAB_LAYOUT);
        setBorder(BorderFactory.createEmptyBorder());
        SpeedSearch.install(this, this::getTitleAt);
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "focusEditor");
        getActionMap().put("focusEditor", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                Component selected = getSelectedComponent();
                if (selected != null) {
                    selected.requestFocusInWindow();
                }
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

    public boolean canCloseAll() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Editor close checks must run on the EDT");
        }
        return List.copyOf(this.editors).stream().allMatch(IEditorPanel::canClose);
    }

    public void closeMatching(Predicate<IEditorPanel> predicate) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Editor tabs must be closed on the EDT");
        }
        for (int i = this.editors.size() - 1; i >= 0; i--) {
            if (predicate.test(this.editors.get(i))) {
                removeTabAt(i);
            }
        }
    }

    @Override
    public void removeTabAt(int index) {
        IEditorPanel editor = this.editors.get(index);
        if (!editor.canClose())
            return;
        editors.remove(index);
        super.removeTabAt(index);
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
