package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaEditorSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.CustomJavaLinkGenerator;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis;
import com.github.minecraft_ta.totalDebugCompanion.model.JavaEditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;

import javax.swing.event.CaretListener;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.nio.file.Path;

/** Java-specific parsing and navigation layered on top of the shared text editor. */
public class AbstractCodeViewPanel extends AbstractTextViewPanel implements JavaEditorContext {

    protected final EditorContext context;
    protected final String identifier;
    private boolean astDisposed;
    protected final JavaEditorAnalysis analysis;

    public AbstractCodeViewPanel(EditorContext context, String identifier, String className) {
        this(context, identifier, className, JavaEditorSource::identity, false);
    }

    protected AbstractCodeViewPanel(
            EditorContext context, String identifier,
            String className,
            Function<String, JavaEditorSource> sourceFactory, boolean diagnostics
    ) {
        this(context, identifier, className, sourceFactory, diagnostics, () -> Path.of(identifier));
    }

    protected AbstractCodeViewPanel(EditorContext context, String identifier, String className,
                                    Function<String, JavaEditorSource> sourceFactory, boolean diagnostics, Supplier<Path> sourcePath) {
        super();
        this.context = context;
        installNavigationHistoryMenu(context.navigation());
        this.identifier = identifier;

        this.editorPane.setLinkGenerator(new CustomJavaLinkGenerator(context.astCache(), identifier, sourcePath, context.navigation()::revealPackage, target -> context.navigation().navigate(target)));
        setSyntaxStyle(RSyntaxTextArea.SYNTAX_STYLE_JAVA);
        this.analysis = new JavaEditorAnalysis(editorPane, context.astCache(), identifier, className,
                sourceFactory, context.analysisExecutor(), diagnostics);
    }

    @Override
    protected void applyAdditionalSyntaxColors(SyntaxScheme scheme, EditorPalette palette) {
        CodeUtils.initJavaSemanticColors(scheme, palette);
    }

    @Override public JavaAnalysis currentSnapshot() { return analysis.currentSnapshot(); }

    @Override public ASTCache astCache() { return context.astCache(); }

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
            this.analysis.close();
        }
        super.dispose();
    }
}
