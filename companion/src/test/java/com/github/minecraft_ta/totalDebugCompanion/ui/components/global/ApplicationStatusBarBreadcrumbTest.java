package com.github.minecraft_ta.totalDebugCompanion.ui.components.global;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysisFixtures;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaEditorSource;
import com.github.minecraft_ta.totalDebugCompanion.model.IEditorPanel;
import com.github.minecraft_ta.totalDebugCompanion.model.JavaEditorContext;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.RuntimeMember;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationStatusBarBreadcrumbTest extends StatusBarTestFixture {
    @Test void scriptBreadcrumbsExcludeGeneratedMembersAndMapRealDeclarations() throws Exception {
        try (var index = JavaAnalysisFixtures.index()) {
            CompanionClassIndex.set(index);
            String body = "return 1;";
            var file = new NavigationTarget.LocalFile(Path.of("Proof.tdscript"));
            check(body, JavaSnippetSource.body("Proof", body).editorSource(), body.indexOf("1"), file, "run()", null);
            String local = "import java.lang.String;\nclass Local { void update() { int count = 1; } }";
            check(local, JavaSnippetSource.body("Proof", local).editorSource(), local.indexOf("count"), file,
                    "update()", new NavigationTarget.LocalFile(file.path(), local.indexOf("update")));
        } finally { CompanionClassIndex.clear(); }
    }

    @Test void ordinaryJavaKeepsLocalOffsetsAndSemanticRuntimeTargets() throws Exception {
        try (var index = JavaAnalysisFixtures.index()) {
            CompanionClassIndex.set(index);
            String text = "class Proof { void update() { int count = 1; } }";
            var file = new NavigationTarget.LocalFile(Path.of("Proof.java"));
            check(text, JavaEditorSource.identity(text), text.indexOf("count"), file,
                    "update()", new NavigationTarget.LocalFile(file.path(), text.indexOf("update")));
            check(text, JavaEditorSource.identity(text), text.indexOf("count"), new NavigationTarget.RuntimeClass("Proof"),
                    "update()", new NavigationTarget.RuntimeDeclaration(new RuntimeMember.Method("Proof", "update", "()V")));
        } finally { CompanionClassIndex.clear(); }
    }

    private void check(String text, JavaEditorSource source, int caret, NavigationTarget target,
                              String label, NavigationTarget expected) throws Exception {
        var snapshot = JavaAnalysis.parse("Proof", text, source, 0, CompanionClassIndex.identity());
        var context = new JavaEditorContext() {
            private final ASTCache cache = new ASTCache();
            @Override public ASTCache astCache() { return cache; }
            @Override public String astKey() { return "Proof"; }
            @Override public int caretOffset() { return caret; }
            @Override public JavaAnalysis currentSnapshot() { return snapshot; }
            @Override public Runnable addCaretOffsetListener(IntConsumer listener) { return () -> {}; }
        };
        var navigated = new AtomicReference<NavigationTarget>();
        SwingUtilities.invokeAndWait(() -> {
            var bar = statusBar(navigated::set);
            try {
                bar.setEditor(new IEditorPanel() {
                    @Override public String getTitle() { return "Proof"; }
                    @Override public String getTooltip() { return "Proof"; }
                    @Override public Icon getIcon() { return null; }
                    @Override public Component getComponent() { return new JPanel(); }
                    @Override public NavigationTarget getNavigationTarget() { return target; }
                    @Override public JavaEditorContext getJavaEditorContext() { return context; }
                });
                bar.refreshMember();
                JButton member = findButton(bar, label);
                if (expected == null) assertNull(member, "Hidden wrapper members must not appear in breadcrumbs");
                else {
                    assertNotNull(member);
                    member.doClick(0);
                    assertEquals(expected, navigated.get());
                }
            } finally { bar.dispose(); }
        });
    }

    private static JButton findButton(Container parent, String label) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && label.equals(button.getText())) return button;
            if (child instanceof Container nested) {
                JButton found = findButton(nested, label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
