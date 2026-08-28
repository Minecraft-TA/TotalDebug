package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;

import javax.swing.AbstractListModel;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/** Stack-frame list and its selection behavior. */
final class DebuggerFramesPane extends JPanel {
    private final FrameListModel model = new FrameListModel();
    private final JList<DebugEngine.StackFrame> frames = new JList<>(this.model);

    DebuggerFramesPane(
            IntConsumer selection,
            DebuggerPanel.FrameNavigation navigation
    ) {
        super(new BorderLayout());
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(navigation, "navigation");

        JLabel heading = new JLabel("Frames");
        heading.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        setBorder(DynamicMatteBorder.separatorRule(0, 0, 0, 1));
        add(heading, BorderLayout.NORTH);

        this.frames.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.frames.setFixedCellHeight(UiMetrics.TREE_ROW_HEIGHT);
        this.frames.setCellRenderer(new FrameRenderer());
        this.frames.putClientProperty("List.isFileList", false);
        this.frames.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                selection.accept(this.frames.getSelectedIndex());
            }
        });
        this.frames.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    DebugEngine.StackFrame frame = frames.getSelectedValue();
                    if (frame != null) {
                        navigation.open(frame, true);
                    }
                }
            }
        });

        JScrollPane scroll = new JScrollPane(this.frames);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
    }

    void setFrames(List<DebugEngine.StackFrame> frames) {
        this.model.setFrames(frames);
    }

    DebugEngine.StackFrame frame(int row) {
        return this.model.frame(row);
    }

    void selectFirst() {
        this.frames.setSelectedIndex(0);
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
                    list.getSelectionBackground()
            );
            this.label.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            this.label.setToolTipText(source);
            return this.label;
        }

        private static String sourceName(URI uri) {
            if (uri == null || uri.getPath() == null) {
                return "Unknown source";
            }
            String path = uri.getPath();
            int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return path.substring(separator + 1);
        }
    }
}
