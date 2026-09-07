package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.ShadowedTokenTypes;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.EventListenerList;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Java expression editor with optional inline expansion and semantic coloring. */
public final class JavaExpressionField extends RSyntaxTextArea {
    private final JScrollPane component = new JScrollPane(this);
    private final JPanel container = new JPanel(new BorderLayout());
    private final JPanel actions = new JPanel(new BorderLayout());
    private final JPanel actionRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.TRAILING, 0, 0));
    private final FlatIconButton expand = new FlatIconButton(Icons.EXPAND_EDITOR, false);
    private boolean expandable;
    private final EventListenerList listeners = new EventListenerList();
    private final AtomicLong semanticRevision = new AtomicLong();
    private final Timer semanticTimer = new Timer(60, event -> requestSemanticTokens());
    private SemanticTokenProvider semanticTokenProvider;
    private String placeholder = "";
    private boolean multiline;

    public JavaExpressionField() {
        this(0);
    }

    public JavaExpressionField(int columns) {
        super(1, columns);
        setLineWrap(false);
        setHighlightCurrentLine(false);
        setCodeFoldingEnabled(false);
        CodeUtils.initSyntaxScheme(this);
        setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        applyFieldColors();
        this.component.setBorder(UIManager.getBorder("TextField.border"));
        this.component.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        this.component.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        this.component.getViewport().setBorder(null);
        this.container.setBorder(UIManager.getBorder("TextField.border"));
        this.component.setBorder(BorderFactory.createEmptyBorder());
        this.actions.setOpaque(false);
        this.expand.setMargin(new Insets(2, 4, 2, 4));
        this.expand.setToolTipText("Expand editor");
        this.expand.getAccessibleContext().setAccessibleName("Expand editor");
        this.expand.addActionListener(event -> {
            setMultiline(!this.multiline);
            requestFocusInWindow();
        });
        this.actionRow.setOpaque(false);
        this.actionRow.add(this.expand);
        this.actions.add(this.actionRow, BorderLayout.NORTH);
        this.actions.setVisible(false);
        this.container.add(this.component, BorderLayout.CENTER);
        this.container.add(this.actions, BorderLayout.EAST);
        this.semanticTimer.setRepeats(false);
        ((AbstractDocument) getDocument()).setDocumentFilter(new SingleLineFilter());
        getDocument().addDocumentListener((DocumentChangeListener) this::documentChanged);
        getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ctrl ENTER"), "evaluateFragment");
        getActionMap().put("evaluateFragment", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { postActionEvent(); }
        });
    }

    public boolean isMultiline() { return this.multiline; }

    public void setMultiline(boolean multiline) {
        boolean previous = this.multiline;
        this.multiline = multiline;
        ((AbstractDocument) getDocument()).setDocumentFilter(multiline ? null : new SingleLineFilter());
        setRows(multiline ? 7 : 1);
        this.component.setVerticalScrollBarPolicy(multiline ? JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                : JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        this.expand.setIcon(multiline ? Icons.COLLAPSE_EDITOR : Icons.EXPAND_EDITOR);
        this.expand.setToolTipText(multiline ? "Collapse editor" : "Expand editor");
        this.expand.getAccessibleContext().setAccessibleName(this.expand.getToolTipText());
        this.container.revalidate();
        firePropertyChange("multiline", previous, multiline);
    }

    public void setExpandable(boolean expandable) {
        this.expandable = expandable;
        this.actions.setVisible(expandable);
    }

    public void addInlineAction(javax.swing.JButton button) {
        this.actionRow.add(button);
    }

    public JComponent component() {
        return this.container;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = Objects.requireNonNullElse(placeholder, "");
        repaint();
    }

    public void setSemanticTokenProvider(SemanticTokenProvider provider) {
        this.semanticTokenProvider = provider;
        if (provider == null) {
            this.semanticRevision.incrementAndGet();
            this.semanticTimer.stop();
            applySemanticTokens(List.of());
        } else {
            scheduleSemanticTokens();
        }
    }

    public void addActionListener(ActionListener listener) {
        this.listeners.add(ActionListener.class, listener);
    }

    public void postActionEvent() {
        ActionEvent event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, getText());
        for (ActionListener listener : this.listeners.getListeners(ActionListener.class)) {
            listener.actionPerformed(event);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (this.component != null) {
            this.component.setEnabled(enabled);
        }
        if (this.expand != null) this.expand.setEnabled(enabled);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (getSyntaxScheme() != null) {
            CodeUtils.initJavaColors(getSyntaxScheme());
        }
        if (this.container != null) {
            this.container.setBorder(UIManager.getBorder("TextField.border"));
        }
        applyFieldColors();
    }

    private void applyFieldColors() {
        Color background = UIManager.getColor("TextField.background");
        Color foreground = UIManager.getColor("TextField.foreground");
        Color caret = UIManager.getColor("TextField.caretForeground");
        if (background != null) {
            setBackground(background);
            if (this.container != null) this.container.setBackground(background);
        }
        if (foreground != null) {
            setForeground(foreground);
        }
        if (caret != null) {
            setCaretColor(caret);
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (!getText().isEmpty() || isFocusOwner() || this.placeholder.isEmpty()) {
            return;
        }
        Insets insets = getInsets();
        Color placeholderColor = UIManager.getColor("TextField.placeholderForeground");
        graphics.setColor(placeholderColor == null ? getDisabledTextColor() : placeholderColor);
        graphics.setFont(getFont());
        graphics.drawString(this.placeholder, insets.left,
                insets.top + graphics.getFontMetrics().getAscent());
    }

    private void documentChanged(DocumentEvent event) {
        if (event.getType() != DocumentEvent.EventType.CHANGE) {
            scheduleSemanticTokens();
        }
    }

    private void scheduleSemanticTokens() {
        this.semanticRevision.incrementAndGet();
        this.semanticTimer.restart();
    }

    private void requestSemanticTokens() {
        long revision = this.semanticRevision.get();
        String expression = getText();
        SemanticTokenProvider provider = this.semanticTokenProvider;
        if (provider == null || expression.isBlank() || !isEnabled()) {
            applySemanticTokens(List.of());
            return;
        }
        CompletableFuture<List<DebugEngine.ExpressionToken>> request;
        try {
            request = Objects.requireNonNull(provider.tokens(expression), "semantic token provider result");
        } catch (RuntimeException ignored) {
            applySemanticTokens(List.of());
            return;
        }
        request.whenComplete((tokens, failure) -> SwingUtilities.invokeLater(() -> {
            if (revision != this.semanticRevision.get() || !expression.equals(getText())) {
                return;
            }
            applySemanticTokens(failure == null && tokens != null ? tokens : List.of());
        }));
    }

    private void applySemanticTokens(List<DebugEngine.ExpressionToken> tokens) {
        Map<Integer, Integer> tokenTypes = new LinkedHashMap<>();
        for (DebugEngine.ExpressionToken token : tokens) {
            tokenTypes.put(token.start(), switch (token.kind()) {
                case TYPE -> ShadowedTokenTypes.TYPE;
                case FIELD -> ShadowedTokenTypes.FIELD;
                case METHOD -> TokenTypes.FUNCTION;
            });
        }
        CustomJavaTokenMaker tokenMaker = tokenMaker();
        if (tokenMaker != null) {
            tokenMaker.setSemanticTokenTypes(tokenTypes, this);
        } else if (!tokenTypes.isEmpty()) {
            throw new IllegalStateException("Runtime expression coloring requires CustomJavaTokenMaker");
        }
    }

    private CustomJavaTokenMaker tokenMaker() {
        try {
            RSyntaxDocument document = (RSyntaxDocument) getDocument();
            Field field = RSyntaxDocument.class.getDeclaredField("tokenMaker");
            field.setAccessible(true);
            Object tokenMaker = field.get(document);
            return tokenMaker instanceof CustomJavaTokenMaker custom ? custom : null;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to access the Java expression token maker", exception);
        }
    }

    @FunctionalInterface
    public interface SemanticTokenProvider {
        CompletableFuture<List<DebugEngine.ExpressionToken>> tokens(String expression);
    }

    private final class SingleLineFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass bypass, int offset, String text, AttributeSet attributes)
                throws BadLocationException {
            super.insertString(bypass, offset, oneLine(text), attributes);
        }

        @Override
        public void replace(
                FilterBypass bypass,
                int offset,
                int length,
                String text,
                AttributeSet attributes
        ) throws BadLocationException {
            super.replace(bypass, offset, length, oneLine(text), attributes);
        }

        private String oneLine(String text) {
            if (expandable && text != null && (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)) {
                setMultiline(true);
                return text;
            }
            return text == null ? null : text.replace('\r', ' ').replace('\n', ' ');
        }
    }
}
