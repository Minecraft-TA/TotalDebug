package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyRelation;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclaration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.SwingUtilities;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeVisionDisposalTest {
    @Test
    void closingControllerUnsubscribesWithoutRemovingOtherEditorListeners() throws Exception {
        var field = ASTCache.class.getDeclaredField("LISTENERS");
        field.setAccessible(true);
        var listeners = (Map<?, ?>) field.get(null);
        String key = "code-vision-disposal-test";
        Runnable unsubscribeOther = ASTCache.addChangeListener(key, (unit, version) -> {});
        try (var service = new CodeInsightService(() -> { throw new AssertionError("No analysis expected"); }, RuntimeSourceCatalog.empty())) {
            SwingUtilities.invokeAndWait(() -> {
                var editor = new RSyntaxTextArea();
                var scroll = new RTextScrollPane(editor);
                var layerUI = new CodeVisionLayerUI(editor, new CodeVisionLayerUI.Handler() {
                    public void showUsages(CodeSymbol symbol) {}
                    public void showHierarchy(CodeSymbol symbol, HierarchyRelation relation, int count, int offset) {}
                    public void showDebuggerValue(DebuggerInlineValueHints.ValueHint value) {}
                });
                var layer = new JLayer<JComponent>(scroll, layerUI);
                var gutter = new HierarchyGutterMarkers(new EditorGutter(scroll.getGutter()), new HierarchyGutterMarkers.Handler() {
                    public void navigate(SourceDeclaration declaration, HierarchyRelation relation, int count) {}
                    public void preview(SourceDeclaration declaration, HierarchyRelation relation, int count, boolean mixed) {}
                    public void hidePreview() {}
                });
                var controller = new CodeVisionController(key, service, layerUI, layer, gutter);
                assertEquals(2, ((Collection<?>) listeners.get(key)).size());
                controller.close();
                controller.close();
                assertEquals(1, ((Collection<?>) listeners.get(key)).size());
            });
        } finally { unsubscribeOther.run(); ASTCache.removeFromCache(key); }
    }
}
