package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaEditorSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.CustomJavaLinkGenerator;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.model.JavaEditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;

import javax.swing.event.DocumentEvent;
import javax.swing.event.CaretListener;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.Function;

/** Java-specific parsing and navigation layered on top of the shared text editor. */
public class AbstractCodeViewPanel extends AbstractTextViewPanel implements JavaEditorContext {

    protected final EditorContext context;
    protected final String identifier;
    private boolean astDisposed;

    public AbstractCodeViewPanel(EditorContext context, String identifier, String className) {
        this(context, identifier, className, JavaEditorSource::identity);
    }

    protected AbstractCodeViewPanel(
            EditorContext context, String identifier,
            String className,
            Function<String, JavaEditorSource> sourceFactory
    ) {
        super();
        this.context = context;
        installNavigationHistoryMenu(context.navigation());
        this.identifier = identifier;

        this.editorPane.setLinkGenerator(new CustomJavaLinkGenerator(identifier, context.navigation()::revealPackage, target -> context.navigation().navigate(target)));
        this.editorPane.getDocument().addDocumentListener((DocumentChangeListener) event -> {
            if (event.getType() == DocumentEvent.EventType.CHANGE) {
                return;
            }
            String editorText = UIUtils.getText(this.editorPane);
            ASTCache.update(identifier, className, editorText, sourceFactory.apply(editorText));
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
    public String astKey() {
        return this.identifier;
    }

    @Override
    public int caretOffset() {
        return this.editorPane.getCaretPosition();
    }

    @Override
    public Runnable addCaretOffsetListener(IntConsumer listener) {
        Objects.requireNonNull(listener, "listener");
        CaretListener caretListener = event -> listener.accept(event.getDot());
        this.editorPane.addCaretListener(caretListener);
        return () -> this.editorPane.removeCaretListener(caretListener);
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
