package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.CustomJavaLinkGenerator;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;

import javax.swing.event.DocumentEvent;

/** Java-specific parsing and navigation layered on top of the shared text editor. */
public class AbstractCodeViewPanel extends AbstractTextViewPanel {

    protected final String identifier;
    private boolean astDisposed;

    public AbstractCodeViewPanel(String identifier, String className) {
        super();
        this.identifier = identifier;

        this.editorPane.setLinkGenerator(new CustomJavaLinkGenerator(identifier));
        this.editorPane.addHyperlinkListener(event -> {
        });
        this.editorPane.getDocument().addDocumentListener((DocumentChangeListener) event -> {
            if (event.getType() == DocumentEvent.EventType.CHANGE) {
                return;
            }
            ASTCache.update(identifier, className, UIUtils.getText(this.editorPane));
        });

        setSyntaxStyle(RSyntaxTextArea.SYNTAX_STYLE_JAVA);
        try {
            var document = this.editorPane.getDocument();
            var field = document.getClass().getDeclaredField("tokenMaker");
            field.setAccessible(true);
            ((CustomJavaTokenMaker) field.get(document)).setASTKey(identifier, this.editorPane);
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    @Override
    protected void applyAdditionalSyntaxColors(SyntaxScheme scheme, EditorPalette palette) {
        CodeUtils.initJavaSemanticColors(scheme, palette);
    }

    @Override
    public void dispose() {
        if (!this.astDisposed) {
            this.astDisposed = true;
            ASTCache.removeFromCache(this.identifier);
        }
        super.dispose();
    }
}
