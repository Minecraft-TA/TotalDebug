package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionProposal;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionRange;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupChrome;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JWindow;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Keyboard-driven completion for the subset of Java expressions supported by the debugger. */
public final class ExpressionCompletionSupport implements AutoCloseable {
    private static final String NEXT = "debugExpressionCompletion.next";
    private static final String PREVIOUS = "debugExpressionCompletion.previous";
    private static final String ACCEPT_ENTER = "debugExpressionCompletion.acceptEnter";
    private static final String ACCEPT_TAB = "debugExpressionCompletion.acceptTab";
    private static final String SHOW = "debugExpressionCompletion.show";
    private static final int ROW_HEIGHT = 24;

    private final JTextField field;
    private final DefaultListModel<DebuggerCompletionProposal> model = new DefaultListModel<>();
    private final JList<DebuggerCompletionProposal> list = new JList<>(this.model);
    private final JScrollPane content = new JScrollPane(this.list);
    private final DocumentListener documentListener = (DocumentChangeListener) this::documentChanged;
    private final FocusAdapter focusListener = new FocusAdapter() {
        @Override
        public void focusLost(FocusEvent event) {
            hidePopup();
        }
    };
    private CompletionProvider completionProvider;
    private final AtomicLong completionRevision = new AtomicLong();
    private JWindow popup;
    private boolean applying;

    public ExpressionCompletionSupport(JTextField field) {
        this.field = Objects.requireNonNull(field, "field");
        configurePopup();
        configureKeys();
        this.field.getDocument().addDocumentListener(this.documentListener);
        this.field.addFocusListener(this.focusListener);
    }

    public void setCompletionProvider(CompletionProvider completionProvider) {
        this.completionProvider = completionProvider;
        this.completionRevision.incrementAndGet();
        if (isCompletionVisible()) {
            updatePopup(true);
        }
    }

    private void configurePopup() {
        this.list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.list.setFixedCellHeight(ROW_HEIGHT);
        this.list.setCellRenderer((ListCellRenderer<DebuggerCompletionProposal>) (list, value, index, selected, focus) -> {
            PrimarySecondaryLabel label = new PrimarySecondaryLabel();
            label.configure(
                    new PrimarySecondaryText(value.label(), value.detail()),
                    switch (value.kind()) {
                        case VARIABLE -> Icons.JAVA_VARIABLE;
                        case FIELD, CONSTANT -> Icons.FIELD;
                        case METHOD -> Icons.JAVA_METHOD;
                        case TYPE -> Icons.JAVA_CLASS;
                        case KEYWORD -> null;
                    },
                    list.getFont(),
                    selected,
                    list.getSelectionForeground(),
                    list.getSelectionBackground()
            );
            label.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            return label;
        });
        this.list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    acceptSelection();
                }
            }
        });
        this.content.setBorder(PopupChrome.border());
    }

    private void configureKeys() {
        bind("DOWN", NEXT, 1);
        bind("UP", PREVIOUS, -1);
        this.field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), ACCEPT_ENTER);
        this.field.getActionMap().put(ACCEPT_ENTER, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (isCompletionVisible()) {
                    acceptSelection();
                } else {
                    field.postActionEvent();
                }
            }
        });
        this.field.setFocusTraversalKeysEnabled(false);
        this.field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("TAB"), ACCEPT_TAB);
        this.field.getActionMap().put(ACCEPT_TAB, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (isCompletionVisible()) {
                    acceptSelection();
                } else {
                    field.transferFocus();
                }
            }
        });
        this.field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl SPACE"), SHOW);
        this.field.getActionMap().put(SHOW, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                updatePopup(true);
            }
        });
    }

    private void bind(String keyStroke, String actionKey, int direction) {
        this.field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyStroke), actionKey);
        this.field.getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!isCompletionVisible()) {
                    updatePopup(true);
                    return;
                }
                int size = model.size();
                if (size == 0) {
                    return;
                }
                int selected = list.getSelectedIndex();
                list.setSelectedIndex(Math.floorMod(selected + direction, size));
                list.ensureIndexIsVisible(list.getSelectedIndex());
            }
        });
    }

    private void documentChanged(DocumentEvent event) {
        if (event.getType() == DocumentEvent.EventType.CHANGE || this.applying) {
            return;
        }
        SwingUtilities.invokeLater(() -> updatePopup(false));
    }

    private void updatePopup(boolean explicit) {
        if (!this.field.isShowing() || !this.field.isEnabled()) {
            hidePopup();
            return;
        }
        DebuggerCompletionRange range = DebuggerCompletionRange.around(
                this.field.getText(), this.field.getCaretPosition()
        );
        if (this.completionProvider == null) {
            hidePopup();
            return;
        }
        long revision = this.completionRevision.incrementAndGet();
        String text = this.field.getText();
        int caret = this.field.getCaretPosition();
        if (!explicit && range.prefix().isEmpty() && !range.memberAccess()) {
            hidePopup();
            return;
        }
        CompletableFuture<List<DebuggerCompletionProposal>> request;
        try {
            request = Objects.requireNonNull(this.completionProvider.complete(text, caret, explicit),
                    "completion provider result");
        } catch (RuntimeException failure) {
            hidePopup();
            return;
        }
        request.whenComplete((matches, failure) -> SwingUtilities.invokeLater(() -> {
            if (revision != this.completionRevision.get()
                    || !text.equals(this.field.getText())
                    || caret != this.field.getCaretPosition()) {
                return;
            }
            if (failure != null || matches == null) {
                hidePopup();
                return;
            }
            showMatches(matches);
        }));
    }

    private void showMatches(List<DebuggerCompletionProposal> matches) {
        matches = List.copyOf(matches);
        this.model.clear();
        this.model.addAll(matches);
        if (matches.isEmpty()) {
            hidePopup();
            return;
        }
        this.list.setSelectedIndex(0);
        int width = Math.max(this.field.getWidth(), 300);
        int height = Math.min(8, matches.size()) * ROW_HEIGHT + 2;
        JWindow completionWindow = popupForFieldOwner();
        completionWindow.setSize(new Dimension(width, height));
        PopupChrome.placeAdjacent(
                completionWindow,
                this.field,
                new Rectangle(0, 0, this.field.getWidth(), this.field.getHeight())
        );
        completionWindow.setVisible(true);
    }

    private void acceptSelection() {
        if (!isCompletionVisible()) {
            return;
        }
        DebuggerCompletionProposal selected = this.list.getSelectedValue();
        if (selected == null) {
            return;
        }
        DebuggerCompletionRange range = DebuggerCompletionRange.around(
                this.field.getText(), this.field.getCaretPosition()
        );
        if ((this.completionProvider != null || selected.replacementEnd() > selected.replacementStart())
                && selected.replacementStart() <= selected.replacementEnd()
                && selected.replacementEnd() <= this.field.getDocument().getLength()
                && selected.replacementStart() <= range.start()) {
            range = new DebuggerCompletionRange(
                    selected.replacementStart(), selected.replacementEnd(), range.prefix(),
                    range.memberAccess(), range.ownerEnd()
            );
        }
        String replacement = this.field.getText().substring(0, range.start())
                + selected.insertionText()
                + this.field.getText().substring(range.end());
        this.applying = true;
        try {
            this.field.setText(replacement);
            this.field.setCaretPosition(range.start() + selected.caretOffset());
        } finally {
            this.applying = false;
        }
        hidePopup();
    }

    boolean isCompletionVisible() {
        return this.popup != null && this.popup.isVisible();
    }

    private JWindow popupForFieldOwner() {
        Window owner = SwingUtilities.getWindowAncestor(this.field);
        if (owner == null) {
            throw new IllegalStateException("Expression completion requires a visible owner window");
        }
        if (this.popup != null && this.popup.getOwner() == owner) {
            return this.popup;
        }
        disposePopup();
        // A second JPopupMenu would replace the breakpoint editor's active menu path.
        this.popup = new JWindow(owner);
        this.popup.setFocusableWindowState(false);
        this.popup.setContentPane(this.content);
        return this.popup;
    }

    private void hidePopup() {
        if (this.popup != null) {
            this.popup.setVisible(false);
        }
    }

    private void disposePopup() {
        if (this.popup == null) {
            return;
        }
        this.popup.dispose();
        this.popup = null;
    }

    @Override
    public void close() {
        this.completionRevision.incrementAndGet();
        disposePopup();
        this.field.getDocument().removeDocumentListener(this.documentListener);
        this.field.removeFocusListener(this.focusListener);
    }

    @FunctionalInterface
    public interface CompletionProvider {
        CompletableFuture<List<DebuggerCompletionProposal>> complete(String text, int caret, boolean explicit);
    }

}
