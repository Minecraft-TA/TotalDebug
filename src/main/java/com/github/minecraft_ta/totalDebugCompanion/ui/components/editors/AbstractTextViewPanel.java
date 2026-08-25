package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.search.SearchManager;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.BottomInformationBar;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.SearchHeaderBar;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.beans.PropertyChangeListener;
import java.util.function.Consumer;

/** Shared RSyntaxTextArea chrome without Java parsing or navigation behavior. */
public abstract class AbstractTextViewPanel extends JPanel {

    public static final Font JETBRAINS_MONO_FONT;

    static {
        FlatJetBrainsMonoFont.install();
        JETBRAINS_MONO_FONT = new Font(FlatJetBrainsMonoFont.FAMILY, Font.PLAIN, 14);
    }

    protected final RSyntaxTextArea editorPane = new RSyntaxTextArea();
    protected final RTextScrollPane editorScrollPane = new RTextScrollPane(this.editorPane);
    protected final EditorChromeLayerUI editorChromeLayerUI = new EditorChromeLayerUI(
            this.editorPane,
            this.editorScrollPane.getGutter()
    );
    protected final JLayer<RTextScrollPane> editorLayer = new JLayer<>(
            this.editorScrollPane,
            this.editorChromeLayerUI
    );
    protected final BottomInformationBar bottomInformationBar;

    protected JComponent headerComponent;

    private final PropertyChangeListener fontSizeListener = event -> updateFonts();
    private final Consumer<CompanionTheme> themeListener = theme -> applyTheme();
    private SearchManager searchManager;
    private boolean disposed;

    protected AbstractTextViewPanel() {
        this(new BottomInformationBar());
    }

    protected AbstractTextViewPanel(BottomInformationBar bottomInformationBar) {
        super(new BorderLayout());
        this.bottomInformationBar = bottomInformationBar;

        Gutter gutter = this.editorScrollPane.getGutter();
        gutter.setBorder(new PaddingOnlyGutterBorder(0, 5, 0, 5));
        gutter.setOpaque(false);
        gutter.setIconRowHeaderInheritsGutterBackground(true);
        this.editorScrollPane.getRowHeader().setOpaque(false);
        this.editorScrollPane.setBorder(BorderFactory.createEmptyBorder());

        this.editorPane.setAnimateBracketMatching(false);
        this.editorPane.setPaintMatchedBracketPair(true);
        this.editorPane.setMatchedBracketBorderColor(null);
        this.editorPane.addCaretListener(event -> this.editorPane.getCaret().setVisible(true));
        this.editorPane.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                editorPane.getCaret().setVisible(true);
            }
        });
        this.editorPane.setSyntaxEditingStyle(RSyntaxTextArea.SYNTAX_STYLE_NONE);

        add(this.editorLayer, BorderLayout.CENTER);
        applyTheme();
        updateFonts();
        GlobalConfig.getInstance().addEditorFontSizeListener(this.fontSizeListener);
        ThemeManager.addThemeChangeListener(this.themeListener);
        addHierarchyListener(event -> {
            if (event.getChangeFlags() == HierarchyEvent.PARENT_CHANGED && getParent() == null) {
                dispose();
            }
        });
    }

    protected final void enableSearch() {
        if (this.searchManager != null) {
            return;
        }
        this.searchManager = new SearchManager(this.editorPane);
        this.editorPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ctrl pressed F"), "openSearchPopup");
        this.editorPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "closeSearchPopup");
        this.editorPane.getActionMap().put("closeSearchPopup", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                removeHeaderComponent();
                searchManager.hideHighlights();
            }
        });
        this.editorPane.getActionMap().put("openSearchPopup", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                setHeaderComponent(new SearchHeaderBar(searchManager));
            }
        });
        this.searchManager.addFocusedIndexChangedListener(index -> {
            if (this.searchManager.getMatchCount() == 0) {
                return;
            }
            SwingUtilities.invokeLater(() -> UIUtils.centerViewportOnRange(
                    this.editorScrollPane,
                    this.searchManager.getFocusedRangeStart(),
                    this.searchManager.getFocusedRangeEnd()
            ));
        });
    }

    protected final void setSyntaxStyle(String syntaxStyle) {
        this.editorPane.setSyntaxEditingStyle(syntaxStyle);
        applyTheme();
    }

    protected void applyTheme() {
        EditorPalette palette = ThemeManager.palette();
        Gutter gutter = this.editorScrollPane.getGutter();
        gutter.setForeground(palette.lineNumber());
        gutter.setBackground(new Color(
                palette.background().getRed(),
                palette.background().getGreen(),
                palette.background().getBlue(),
                0
        ));
        gutter.setBorderColor(palette.indentGuide());
        gutter.setCurrentLineNumberColor(palette.foreground());

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

        SyntaxScheme scheme = this.editorPane.getSyntaxScheme();
        CodeUtils.initSyntaxColors(scheme, palette);
        applyAdditionalSyntaxColors(scheme, palette);
        for (Style style : scheme.getStyles()) {
            if (style != null) {
                style.underline = false;
            }
        }
        this.editorChromeLayerUI.setPalette(palette, this.editorLayer);
        this.editorPane.revalidate();
        this.editorPane.repaint();
    }

    protected void applyAdditionalSyntaxColors(SyntaxScheme scheme, EditorPalette palette) {
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
            var verticalScrollBar = this.editorScrollPane.getVerticalScrollBar();
            verticalScrollBar.setValue((int) (verticalScrollBar.getValue() + component.getPreferredSize().getHeight()));
        });
    }

    public void removeHeaderComponent() {
        if (this.headerComponent == null) {
            return;
        }

        synchronized (getTreeLock()) {
            for (int i = 0; i < getComponentCount(); i++) {
                Component component = getComponent(i);
                if (component == this.headerComponent) {
                    remove(i);
                    revalidate();
                    repaint();
                    var verticalScrollBar = this.editorScrollPane.getVerticalScrollBar();
                    verticalScrollBar.setValue(verticalScrollBar.getValue() - component.getHeight());
                    break;
                }
            }
        }
        this.headerComponent = null;
    }

    protected void updateFonts() {
        Font newFont = JETBRAINS_MONO_FONT.deriveFont(GlobalConfig.getInstance().editorFontSize());
        this.editorPane.setFractionalFontMetricsEnabled(true);
        this.editorPane.setFont(newFont);
        this.editorScrollPane.getGutter().setLineNumberFont(newFont);
    }

    public BottomInformationBar getBottomInformationBar() {
        return this.bottomInformationBar;
    }

    public void dispose() {
        if (this.disposed) {
            return;
        }
        this.disposed = true;
        if (this.searchManager != null) {
            this.searchManager.stopThread();
        }
        GlobalConfig.getInstance().removeEditorFontSizeListener(this.fontSizeListener);
        ThemeManager.removeThemeChangeListener(this.themeListener);
    }

    private static final class PaddingOnlyGutterBorder extends Gutter.GutterBorder {
        private PaddingOnlyGutterBorder(int top, int left, int bottom, int right) {
            super(top, left, bottom, right);
        }

        @Override
        public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
        }
    }
}
