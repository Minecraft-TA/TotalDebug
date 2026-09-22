package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionProposal;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.ShadowedTokenTypes;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.junit.jupiter.api.Test;
import com.github.minecraft_ta.totalDebugCompanion.testui.RequiresDesktop;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import static com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope.onEdt;
import static com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope.await;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.JScrollPane;
import javax.swing.RootPaneContainer;
import java.awt.BorderLayout;
import java.awt.Window;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@UiTest
final class ExpressionCompletionSupportUiTest {
    @Test
    void derivesBreakpointExpressionColorsFromSourceCompletionTruth() throws Exception {
        List<DebugEngine.ExpressionToken> tokens = ExpressionCompletionSemantics.tokens(
                "Blocks.VALUE",
                (expression, caret, explicit) -> CompletableFuture.completedFuture(caret <= 6
                        ? List.of(new DebuggerCompletionProposal(
                        "Blocks", "Blocks", DebuggerCompletionProposal.Kind.TYPE,
                        "sample.Blocks", 0, 6))
                        : List.of(new DebuggerCompletionProposal(
                        "VALUE", "VALUE", DebuggerCompletionProposal.Kind.CONSTANT,
                        "int", 7, 12)))
        ).get();
        assertEquals(List.of(
                new DebugEngine.ExpressionToken(0, 6, DebugEngine.ExpressionTokenKind.TYPE),
                new DebugEngine.ExpressionToken(7, 5, DebugEngine.ExpressionTokenKind.FIELD)
        ), tokens);
    }

    @Test
    void appliesRuntimeSemanticTokensToJavaExpressions() throws Exception {
        AbstractTokenMakerFactory mappings = (AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance();
        mappings.putMapping(RSyntaxTextArea.SYNTAX_STYLE_JAVA, CustomJavaTokenMaker.class.getName());
        JavaExpressionField field = onEdt(() -> new JavaExpressionField(24));
        onEdt(() -> {
            field.setSemanticTokenProvider(expression -> CompletableFuture.completedFuture(List.of(
                    new DebugEngine.ExpressionToken(0, 6, DebugEngine.ExpressionTokenKind.TYPE),
                    new DebugEngine.ExpressionToken(7, 5, DebugEngine.ExpressionTokenKind.FIELD)
            )));
            field.setText("Blocks.VALUE");
        });
        await(() -> tokenTypeAt(field, 0) == ShadowedTokenTypes.TYPE
                && tokenTypeAt(field, 7) == ShadowedTokenTypes.FIELD);
        assertEquals(ShadowedTokenTypes.TYPE, onEdt(() -> tokenTypeAt(field, 0)));
        assertEquals(ShadowedTokenTypes.FIELD, onEdt(() -> tokenTypeAt(field, 7)));
    }

    @Test
    void opensCompletionForAnEvaluateField() throws Exception {
        Fixture fixture = onEdt(ExpressionCompletionSupportUiTest::evaluateFixture);
        await(fixture.field()::isFocusOwner);
        try {
            onEdt(() -> fixture.field().setText("f"));
            await(fixture.completion()::isCompletionVisible);

            onEdt(() -> {
                assertEquals("f", fixture.field().getText());
                assertTrue(fixture.completion().isCompletionVisible());
                invokeFieldAction(fixture.field(), "TAB");
                assertEquals("false", fixture.field().getText());
                assertFalse(fixture.completion().isCompletionVisible());
            });
        } finally {
            onEdt(fixture::close);
        }
    }

    @RequiresDesktop
    @Test void aLateCompletionCannotReopenAfterTheFieldLosesFocus() throws Exception {
        Fixture fixture = onEdt(ExpressionCompletionSupportUiTest::evaluateFixture);
        await(fixture.field()::isFocusOwner);
        var pending = new CompletableFuture<List<DebuggerCompletionProposal>>();
        var requested = new CompletableFuture<Void>();
        try {
            onEdt(() -> {
                fixture.completion().setCompletionProvider((text, caret, explicit) -> { requested.complete(null); return pending; });
                fixture.field().setText("f");
                fixture.field().setCaretPosition(1);
                assertTrue(fixture.field().getCaret().isVisible());
            });
            requested.get(5, TimeUnit.SECONDS);
            var other = onEdt(() -> {
                var button = new JButton("Other control");
                fixture.frame().add(button, BorderLayout.SOUTH);
                fixture.frame().pack();
                UiTestScope.focus(button);
                return button;
            });
            await(other::isFocusOwner);
            onEdt(() -> {
                assertFalse(fixture.field().getCaret().isVisible());
                pending.complete(List.of(new DebuggerCompletionProposal("false", "false", DebuggerCompletionProposal.Kind.KEYWORD, "", 0, 1, 5, 1)));
                fixture.field().setText("loaded value");
                assertFalse(fixture.field().getCaret().isVisible());
            });
            onEdt(() -> assertFalse(fixture.completion().isCompletionVisible()));
        } finally { onEdt(fixture::close); }
    }

    @Test
    void preservesRequiredImportsWhenACompletionIsAccepted() throws Exception {
        Fixture fixture = onEdt(ExpressionCompletionSupportUiTest::evaluateFixture);
        await(fixture.field()::isFocusOwner);
        try {
            AtomicReference<DebuggerCompletionProposal> accepted = new AtomicReference<>();
            DebuggerCompletionProposal proposal = new DebuggerCompletionProposal(
                    "TotalDebug",
                    "TotalDebug",
                    DebuggerCompletionProposal.Kind.TYPE,
                    "com.github.minecraft_ta.totaldebug.TotalDebug",
                    0,
                    8,
                    10,
                    80,
                    List.of("com.github.minecraft_ta.totaldebug.TotalDebug")
            );
            onEdt(() -> {
                fixture.completion().setCompletionProvider((text, caret, explicit) ->
                        CompletableFuture.completedFuture(List.of(proposal)));
                fixture.completion().setAcceptanceListener(accepted::set);
                fixture.field().setText("TotalDeb");
            });
            await(fixture.completion()::isCompletionVisible);

            onEdt(() -> invokeFieldAction(fixture.field(), "TAB"));

            assertEquals("TotalDebug", onEdt((Callable<String>) fixture.field()::getText));
            assertEquals(proposal.requiredImports(), accepted.get().requiredImports());
        } finally {
            onEdt(fixture::close);
        }
    }

    @Test
    void opensMemberCompletionAtTheFinalDotOfACompoundExpression() throws Exception {
        Fixture fixture = onEdt(ExpressionCompletionSupportUiTest::evaluateFixture);
        await(fixture.field()::isFocusOwner);
        try {
            String expression = "pos.y + pos.";
            onEdt(() -> fixture.completion().setCompletionProvider((text, caret, explicit) ->
                    CompletableFuture.completedFuture(List.of(new DebuggerCompletionProposal(
                            "x", "x", DebuggerCompletionProposal.Kind.FIELD,
                            "long", caret, caret
                    )))));

            onEdt(() -> fixture.field().setText(expression));
            await(fixture.completion()::isCompletionVisible);

            onEdt(() -> {
                assertTrue(fixture.completion().isCompletionVisible());
                invokeFieldAction(fixture.field(), "TAB");
                assertEquals("pos.y + pos.x", fixture.field().getText());
            });
        } finally {
            onEdt(fixture::close);
        }
    }

    @Test
    void givesOneLongCompletionEnoughWidthWithoutHorizontalScrolling() throws Exception {
        Fixture fixture = onEdt(ExpressionCompletionSupportUiTest::evaluateFixture);
        await(fixture.field()::isFocusOwner);
        try {
            onEdt(() -> fixture.completion().setCompletionProvider((text, caret, explicit) ->
                    CompletableFuture.completedFuture(List.of(new DebuggerCompletionProposal(
                            "stateDefinition", "stateDefinition", DebuggerCompletionProposal.Kind.FIELD,
                            "net.minecraft.world.level.block.state.StateDefinition<Block, BlockState>",
                            0, text.length()
                    )))));
            onEdt(() -> fixture.field().setText("stated"));
            await(fixture.completion()::isCompletionVisible);

            onEdt(() -> {
                Window completionWindow = Arrays.stream(fixture.frame().getOwnedWindows())
                        .filter(Window::isVisible)
                        .findFirst()
                        .orElseThrow();
                assertTrue(completionWindow.getWidth() > 300,
                        "A long single result should not be squeezed into the minimum popup width");
                JScrollPane content = (JScrollPane) ((RootPaneContainer) completionWindow)
                        .getContentPane();
                assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                        content.getHorizontalScrollBarPolicy());
            });
        } finally {
            onEdt(fixture::close);
        }
    }

    @Test
    void keepsBreakpointEditorOpenWhenCompletionAppears() throws Exception {
        Fixture fixture = onEdt(ExpressionCompletionSupportUiTest::breakpointFixture);
        await(fixture.field()::isFocusOwner);
        try {
            onEdt(() -> fixture.field().setText("f"));
            await(() -> fixture.completion().isCompletionVisible() || !fixture.parentPopup().isVisible());

            onEdt(() -> {
                assertEquals("f", fixture.field().getText());
                assertTrue(fixture.parentPopup().isVisible(), "Typing must not dismiss the breakpoint editor");
                assertTrue(fixture.completion().isCompletionVisible(), "Completion must be visible beside the editor");
                fixture.field().setText("t");
            });
            await(fixture.completion()::isCompletionVisible);
            onEdt(() -> {
                assertTrue(
                        fixture.parentPopup().isVisible(),
                        "Continuing to type must not dismiss the breakpoint editor"
                );
                invokeFieldAction(fixture.field(), "TAB");
                assertEquals("true", fixture.field().getText());
                assertTrue(fixture.parentPopup().isVisible(), "Accepting completion must keep the editor open");
            });
        } finally {
            onEdt(fixture::close);
        }
    }

    @Test
    void ignoresAStaleAsynchronousCompletionResponse() throws Exception {
        Fixture fixture = onEdt(ExpressionCompletionSupportUiTest::evaluateFixture);
        await(fixture.field()::isFocusOwner);
        try {
            List<CompletableFuture<List<DebuggerCompletionProposal>>> requests = new ArrayList<>();
            List<String> requestTexts = new ArrayList<>();
            onEdt(() -> fixture.completion().setCompletionProvider((text, caret, explicit) -> {
                CompletableFuture<List<DebuggerCompletionProposal>> request = new CompletableFuture<>();
                requests.add(request);
                requestTexts.add(text);
                return request;
            }));

            onEdt(() -> fixture.field().setText("f"));
            await(() -> requestTexts.contains("f"));
            onEdt(() -> fixture.field().setText("t"));
            await(() -> requestTexts.contains("t"));
            int currentRequest = requestTexts.lastIndexOf("t");
            int staleRequest = requestTexts.lastIndexOf("f");

            onEdt(() -> requests.get(currentRequest).complete(List.of(new DebuggerCompletionProposal(
                    "true", "true", DebuggerCompletionProposal.Kind.KEYWORD,
                    "boolean literal", 0, 1
            ))));
            await(fixture.completion()::isCompletionVisible);
            assertTrue(onEdt(fixture.completion()::isCompletionVisible));
            onEdt(() -> requests.get(staleRequest).complete(List.of(new DebuggerCompletionProposal(
                    "false", "false", DebuggerCompletionProposal.Kind.KEYWORD,
                    "boolean literal", 0, 1
            ))));
            Thread.sleep(100);
            onEdt(() -> {
                assertTrue(fixture.completion().isCompletionVisible());
                invokeFieldAction(fixture.field(), "TAB");
                assertEquals("true", fixture.field().getText());
            });
        } finally {
            onEdt(fixture::close);
        }
    }

    private static Fixture evaluateFixture() {
        JFrame frame = frame();
        JavaExpressionField field = new JavaExpressionField(24);
        frame.add(field.component(), BorderLayout.CENTER);
        frame.pack();
        UiTestScope.show(frame);
        ExpressionCompletionSupport completion = completion(field);
        UiTestScope.focus(field);
        return new Fixture(frame, null, field, completion);
    }

    private static Fixture breakpointFixture() {
        JFrame frame = frame();
        JButton invoker = new JButton("Breakpoint");
        frame.add(invoker, BorderLayout.CENTER);
        frame.pack();
        UiTestScope.show(frame);

        JavaExpressionField field = new JavaExpressionField(24);
        JPopupMenu editor = new JPopupMenu();
        editor.add(field.component());
        editor.show(invoker, 0, invoker.getHeight());
        ExpressionCompletionSupport completion = completion(field);
        UiTestScope.focus(field);
        return new Fixture(frame, editor, field, completion);
    }

    private static JFrame frame() {
        JFrame frame = new JFrame();
        frame.setLayout(new BorderLayout());
        return frame;
    }

    private static ExpressionCompletionSupport completion(JavaExpressionField field) {
        ExpressionCompletionSupport completion = new ExpressionCompletionSupport(field);
        List<DebuggerCompletionProposal> proposals = List.of(new DebuggerCompletionProposal(
                "false", "false", DebuggerCompletionProposal.Kind.KEYWORD,
                "boolean literal", 0, 0, 5, 80
        ), new DebuggerCompletionProposal(
                "true", "true", DebuggerCompletionProposal.Kind.KEYWORD,
                "boolean literal", 0, 0, 4, 80
        ));
        completion.setCompletionProvider((text, caret, explicit) ->
                CompletableFuture.completedFuture(proposals.stream()
                        .filter(proposal -> proposal.label().startsWith(text.substring(0, Math.min(caret, text.length()))))
                        .map(proposal -> proposal.withRange(0, text.length()))
                        .toList()));
        return completion;
    }

    private static void invokeFieldAction(JavaExpressionField field, String keyStroke) {
        Object actionKey = field.getInputMap().get(KeyStroke.getKeyStroke(keyStroke));
        field.getActionMap().get(actionKey).actionPerformed(null);
    }

    private static int tokenTypeAt(JavaExpressionField field, int offset) {
        for (Token token = field.getTokenListForLine(0); token != null && token.isPaintable();
             token = token.getNextToken()) {
            if (offset >= token.getOffset() && offset < token.getEndOffset()) {
                return token.getType();
            }
        }
        return -1;
    }

    private record Fixture(
            JFrame frame,
            JPopupMenu parentPopup,
            JavaExpressionField field,
            ExpressionCompletionSupport completion
    ) implements AutoCloseable {
        @Override
        public void close() {
            this.completion.close();
            if (this.parentPopup != null) {
                this.parentPopup.setVisible(false);
            }
            this.frame.dispose();
        }
    }

}
