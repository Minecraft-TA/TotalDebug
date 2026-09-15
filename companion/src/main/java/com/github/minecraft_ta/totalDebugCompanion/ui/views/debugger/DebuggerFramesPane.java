package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;

import javax.swing.AbstractListModel;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JPopupMenu;
import javax.swing.MenuSelectionManager;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Stack-frame list and its selection behavior. */
final class DebuggerFramesPane extends JPanel {
    private final FrameListModel model = new FrameListModel();
    private final JList<DebugEngine.StackFrame> frames = new JList<>(this.model);
    private final Action open;
    private final Action copyFrame;
    private final Action copyStack;

    DebuggerFramesPane(
            IntConsumer selection,
            DebuggerPanel.FrameNavigation navigation
    ) {
        this(selection, navigation, DebuggerFramesPane::copy);
    }

    DebuggerFramesPane(
            IntConsumer selection,
            DebuggerPanel.FrameNavigation navigation,
            Consumer<String> clipboard
    ) {
        super(new BorderLayout());
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(navigation, "navigation");
        Objects.requireNonNull(clipboard, "clipboard");
        this.open = action("Open source", Icons.JUMP_TO_SOURCE, "ENTER",
                () -> navigation.open(this.frames.getSelectedValue(), true));
        this.copyFrame = action("Copy frame", Icons.COPY, "ctrl C",
                () -> clipboard.accept(frameText(this.frames.getSelectedValue())));
        this.copyStack = action("Copy stack trace", Icons.COPY, "ctrl shift C",
                () -> clipboard.accept(this.model.frames.stream().map(DebuggerFramesPane::frameText)
                        .collect(Collectors.joining(System.lineSeparator()))));
        updateActions();

        JLabel heading = new JLabel("Frames");
        heading.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        setBorder(DynamicMatteBorder.separatorRule(0, 0, 0, 1));
        add(heading, BorderLayout.NORTH);

        this.frames.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.frames.setFixedCellHeight(UiMetrics.TREE_ROW_HEIGHT);
        this.frames.setCellRenderer(new FrameRenderer());
        this.frames.putClientProperty("List.isFileList", false);
        SpeedSearch.install(this.frames, DebuggerFramesPane::searchText);
        this.frames.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateActions();
                selection.accept(this.frames.getSelectedIndex());
            }
        });
        this.frames.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { showPopup(event); }
            @Override public void mouseReleased(MouseEvent event) { showPopup(event); }

            private void showPopup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                int row = rowAt(event);
                if (row < 0) return;
                frames.setSelectedIndex(row);
                createContextMenu().show(frames, event.getX(), event.getY());
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                int row = rowAt(event);
                if (row >= 0 && event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    frames.setSelectedIndex(row);
                    open.actionPerformed(new ActionEvent(frames, ActionEvent.ACTION_PERFORMED, "openFrame"));
                }
            }
        });
        this.frames.getInputMap(WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), "Open source");
        this.frames.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("shift F10"), "frameMenu");
        this.frames.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("CONTEXT_MENU"), "frameMenu");
        this.frames.getActionMap().put("frameMenu", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                int row = frames.getSelectedIndex();
                if (row < 0) return;
                var bounds = frames.getCellBounds(row, row);
                createContextMenu().show(frames, bounds.x, bounds.y + bounds.height);
            }
        });

        JScrollPane scroll = new JScrollPane(this.frames);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
    }

    void setFrames(List<DebugEngine.StackFrame> frames) {
        // A menu opened for an old pause must not act on the next pause's frames.
        var menuPath = MenuSelectionManager.defaultManager().getSelectedPath();
        if (menuPath.length > 0 && menuPath[0] instanceof JPopupMenu menu && menu.getInvoker() == this.frames) {
            MenuSelectionManager.defaultManager().clearSelectedPath();
        }
        this.model.setFrames(frames);
        updateActions();
    }

    private int rowAt(MouseEvent event) {
        int row = this.frames.locationToIndex(event.getPoint());
        return row >= 0 && this.frames.getCellBounds(row, row).contains(event.getPoint()) ? row : -1;
    }

    private Action action(String name, Icon icon, String shortcut, Runnable handler) {
        Action action = new AbstractAction(name, icon) {
            @Override public void actionPerformed(ActionEvent event) {
                if (isEnabled()) handler.run();
            }
        };
        action.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(shortcut));
        this.frames.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(shortcut), name);
        this.frames.getActionMap().put(name, action);
        return action;
    }

    private void updateActions() {
        DebugEngine.StackFrame frame = this.frames.getSelectedValue();
        this.open.setEnabled(frame != null && !frame.binaryName().isBlank() && frame.line() > 0);
        this.copyFrame.setEnabled(frame != null);
        this.copyStack.setEnabled(this.model.getSize() > 0);
    }

    JPopupMenu createContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(this.open);
        menu.addSeparator();
        menu.add(this.copyFrame);
        menu.add(this.copyStack);
        return menu;
    }

    static String frameText(DebugEngine.StackFrame frame) {
        String location = frame.binaryName().isBlank() ? sourceName(frame.sourceUri()) : frame.binaryName();
        if (frame.line() > 0) location += ":" + frame.line();
        return "at " + (frame.name().isBlank() ? "Unknown frame" : frame.name()) + " (" + location + ")";
    }

    private static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    DebugEngine.StackFrame frame(int row) {
        return this.model.frame(row);
    }

    void selectFirst() {
        this.frames.setSelectedIndex(0);
    }

    private static String searchText(DebugEngine.StackFrame frame) {
        return frame.name() + ' ' + frame.binaryName() + ' '
                + sourceName(frame.sourceUri()) + ' ' + frame.line();
    }

    private static String sourceName(URI uri) {
        if (uri == null || uri.getPath() == null) {
            return "Unknown source";
        }
        String path = uri.getPath();
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(separator + 1);
    }

    private static final class FrameListModel extends AbstractListModel<DebugEngine.StackFrame> {
        private List<DebugEngine.StackFrame> frames = List.of();

        void setFrames(List<DebugEngine.StackFrame> replacement) {
            List<DebugEngine.StackFrame> updated = List.copyOf(replacement);
            int previousSize = this.frames.size();
            int updatedSize = updated.size();
            this.frames = updated;

            int sharedSize = Math.min(previousSize, updatedSize);
            if (sharedSize > 0) {
                fireContentsChanged(this, 0, sharedSize - 1);
            }
            if (updatedSize > previousSize) {
                fireIntervalAdded(this, previousSize, updatedSize - 1);
            } else if (previousSize > updatedSize) {
                fireIntervalRemoved(this, updatedSize, previousSize - 1);
            }
        }

        DebugEngine.StackFrame frame(int row) {
            return row < 0 || row >= this.frames.size() ? null : this.frames.get(row);
        }

        @Override
        public int getSize() {
            return this.frames.size();
        }

        @Override
        public DebugEngine.StackFrame getElementAt(int index) {
            return this.frames.get(index);
        }
    }

    private static final class FrameRenderer implements ListCellRenderer<DebugEngine.StackFrame> {
        private final PrimarySecondaryLabel label = new PrimarySecondaryLabel();

        @Override
        public Component getListCellRendererComponent(
                JList<? extends DebugEngine.StackFrame> list,
                DebugEngine.StackFrame frame,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            String source = frame.binaryName().isBlank() ? sourceName(frame.sourceUri()) : frame.binaryName();
            if (frame.line() > 0) {
                source += ":" + frame.line();
            }
            this.label.configure(
                    new PrimarySecondaryText(frame.name(), source),
                    Icons.JAVA_METHOD,
                    list.getFont(),
                    selected,
                    list.getSelectionForeground(),
                    list.getSelectionBackground(),
                    list
            );
            this.label.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            this.label.setToolTipText(source);
            return this.label;
        }

    }
}
