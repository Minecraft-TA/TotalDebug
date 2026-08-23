package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.CustomJavaLinkGenerator;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.BottomInformationBar;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.beans.PropertyChangeListener;
import java.util.function.Consumer;

public class AbstractCodeViewPanel extends JPanel {

    /**
     * JetBrains Mono, from the {@code flatlaf-fonts-jetbrains-mono} artifact rather than a TTF
     * vendored in this repo. {@code install()} is idempotent, and is called here as well as during
     * startup so the font resolves even when a panel is built outside the normal launch path.
     */
    public static final Font JETBRAINS_MONO_FONT;
    static {
        FlatJetBrainsMonoFont.install();
        JETBRAINS_MONO_FONT = new Font(FlatJetBrainsMonoFont.FAMILY, Font.PLAIN, 14);
    }

    protected final RSyntaxTextArea editorPane = new RSyntaxTextArea() {
        @Override
        public boolean getUnderlineForToken(Token t) {
            return false;
        }
    };
    protected final RTextScrollPane editorScrollPane = new RTextScrollPane(editorPane);

    protected final BottomInformationBar bottomInformationBar = new BottomInformationBar();
    protected final String identifier;

    protected JComponent headerComponent;

    public AbstractCodeViewPanel(String identifier, String className) {
        super(new BorderLayout());
        this.identifier = identifier;

        this.editorScrollPane.getGutter().setBorder(new CompoundBorder(
                DynamicMatteBorder.separatorRule(0, 0, 0, 1),
                BorderFactory.createEmptyBorder(0, 5, 0, 5)
        ));
        this.editorScrollPane.setBorder(BorderFactory.createEmptyBorder());

        this.editorPane.setAnimateBracketMatching(false);
        this.editorPane.setPaintMatchedBracketPair(true);
        this.editorPane.setMatchedBracketBorderColor(null);
        this.editorPane.setLinkGenerator(new CustomJavaLinkGenerator(identifier, this.bottomInformationBar));
        this.editorPane.addHyperlinkListener(e -> {}); //Empty listener to circumvent RSyntaxTextArea bug
        this.editorPane.getDocument().addDocumentListener((DocumentChangeListener) e -> {
            if (e.getType() == DocumentEvent.EventType.CHANGE)
                return;

            ASTCache.update(identifier, className, UIUtils.getText(this.editorPane));
        });
        this.editorPane.addCaretListener(e -> this.editorPane.getCaret().setVisible(true));
        this.editorPane.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                editorPane.getCaret().setVisible(true);
            }
        });

        this.editorPane.setSyntaxEditingStyle(RSyntaxTextArea.SYNTAX_STYLE_JAVA);
        applyTheme();
        try {
            var document = this.editorPane.getDocument();
            var field = document.getClass().getDeclaredField("tokenMaker");
            field.setAccessible(true);
            ((CustomJavaTokenMaker) field.get(document)).setASTKey(identifier, this.editorPane);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }

        add(this.editorScrollPane, BorderLayout.CENTER);
        add(this.bottomInformationBar, BorderLayout.SOUTH);

        updateFonts();

        PropertyChangeListener fontSizeListener = event -> updateFonts();
        GlobalConfig.getInstance().addEditorFontSizeListener(fontSizeListener);
        // The editor resolves its own colours, so FlatLaf.updateUI() alone would leave it stale.
        Consumer<CompanionTheme> themeListener = theme -> applyTheme();
        ThemeManager.addThemeChangeListener(themeListener);
        addHierarchyListener(e -> {
            if (e.getChangeFlags() == HierarchyEvent.PARENT_CHANGED && getParent() == null) {
                ASTCache.removeFromCache(identifier);
                GlobalConfig.getInstance().removeEditorFontSizeListener(fontSizeListener);
                ThemeManager.removeThemeChangeListener(themeListener);
            }
        });
    }

    /** Re-reads every colour this panel resolves itself. Safe to call repeatedly. */
    protected void applyTheme() {
        EditorPalette palette = ThemeManager.palette();

        Gutter gutter = this.editorScrollPane.getGutter();
        gutter.setForeground(palette.lineNumber());
        gutter.setBackground(palette.background());
        gutter.setBorderColor(palette.indentGuide());

        this.editorPane.setBackground(palette.background());
        this.editorPane.setForeground(palette.foreground());
        this.editorPane.setCaretColor(palette.caret());
        this.editorPane.setCurrentLineHighlightColor(palette.currentLine());
        this.editorPane.setMatchedBracketBGColor(palette.matchedBracket());
        this.editorPane.setHyperlinkForeground(palette.hyperlink());

        Color selection = UIManager.getColor("EditorPane.selectionBackground");
        if (selection != null) {
            this.editorPane.setSelectionColor(selection);
        }

        CodeUtils.initJavaColors(this.editorPane.getSyntaxScheme(), palette);
        this.editorPane.revalidate();
        this.editorPane.repaint();
    }

    public void centerViewportOnOffset(int offset) {
        SwingUtilities.invokeLater(() -> UIUtils.centerViewportOnRange(this.editorScrollPane, offset, offset));
    }

    public void setHeaderComponent(JComponent component) {
        removeHeaderComponent();
        this.headerComponent = component;
        add(component, BorderLayout.NORTH);
        revalidate();
        repaint();
        SwingUtilities.invokeLater(() -> {
            //Adjust scroll bar to keep it in place
            var verticalScrollBar = editorScrollPane.getVerticalScrollBar();
            verticalScrollBar.setValue((int) (verticalScrollBar.getValue() + component.getPreferredSize().getHeight()));
        });
    }

    public void removeHeaderComponent() {
        if (this.headerComponent == null)
            return;

        synchronized (getTreeLock()) {
            var i = 0;
            for (; i < getComponentCount(); i++) {
                var component = getComponent(i);
                if (component == this.headerComponent) {
                    remove(i);
                    revalidate();
                    repaint();
                    //Adjust scroll bar to keep it in place
                    var verticalScrollBar = editorScrollPane.getVerticalScrollBar();
                    verticalScrollBar.setValue(verticalScrollBar.getValue() - component.getHeight());
                    break;
                }
            }
        }

        this.headerComponent = null;
    }

    protected void updateFonts() {
        var newFont = JETBRAINS_MONO_FONT.deriveFont(GlobalConfig.getInstance().editorFontSize());
        this.editorPane.setFractionalFontMetricsEnabled(true);
        this.editorPane.setFont(newFont);

        this.editorScrollPane.getGutter().setLineNumberFont(newFont);
    }

    public BottomInformationBar getBottomInformationBar() {
        return this.bottomInformationBar;
    }
}
