package com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting;

import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Token;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class CustomJavaTokenMakerTest {
    @Test
    void keepsMethodColorAfterTypingBeforeItWhileParsingIsPending() throws Exception {
        onEdt(() -> {
            RSyntaxTextArea area = editor("// note\nreceiver.doWork();\nother.later();");
            assertEquals(Token.FUNCTION, tokenType(area, "doWork"));
            assertEquals(Token.FUNCTION, tokenType(area, "later"));

            area.insert("x", 3);

            assertEquals(Token.FUNCTION, tokenType(area, "doWork"));
            assertEquals(Token.FUNCTION, tokenType(area, "later"));
            return null;
        });
    }

    @Test
    void keepsMethodColorAfterDeletingBeforeItWhileParsingIsPending() throws Exception {
        onEdt(() -> {
            RSyntaxTextArea area = editor("// note\nreceiver.doWork();\nother.later();");
            assertEquals(Token.FUNCTION, tokenType(area, "doWork"));

            area.getDocument().remove(3, 1);

            assertEquals(Token.FUNCTION, tokenType(area, "doWork"));
            assertEquals(Token.FUNCTION, tokenType(area, "later"));
            return null;
        });
    }

    @Test
    void keepsColorsAcrossNewlinesAndEditsAtTheStartOfTheDocument() throws Exception {
        onEdt(() -> {
            RSyntaxTextArea area = editor("doWork();\nother.later();");
            area.insert("\n ", 0);
            assertEquals(Token.FUNCTION, tokenType(area, "doWork"));
            assertEquals(Token.FUNCTION, tokenType(area, "later"));

            area.getDocument().remove(0, 2);
            assertEquals(Token.FUNCTION, tokenType(area, "doWork"));
            assertEquals(Token.FUNCTION, tokenType(area, "later"));
            return null;
        });
    }

    @Test
    void dropsOnlyTheEditedNameWhenAnIdentifierChanges() throws Exception {
        onEdt(() -> {
            RSyntaxTextArea area = editor("receiver.doWork();\nother.later();");
            area.insert("X", area.getText().indexOf("Work"));

            assertEquals(Token.IDENTIFIER, tokenType(area, "doXWork"));
            assertEquals(Token.FUNCTION, tokenType(area, "later"));
            return null;
        });
    }

    @Test
    void doesNotApplyOldColorsToAnExtendedIdentifier() throws Exception {
        onEdt(() -> {
            RSyntaxTextArea area = editor("receiver.doWork();\nother.later();");
            area.insert("More", area.getText().indexOf("doWork") + "doWork".length());

            assertEquals(Token.IDENTIFIER, tokenType(area, "doWorkMore"));
            assertEquals(Token.FUNCTION, tokenType(area, "later"));
            return null;
        });
    }

    @Test
    void deletionThroughAMethodDoesNotColorTheReplacement() throws Exception {
        onEdt(() -> {
            RSyntaxTextArea area = editor("receiver.doWork();\nother.later();");
            int methodOffset = area.getText().indexOf("doWork");
            area.replaceRange("changed", methodOffset, methodOffset + "doWork".length());

            assertEquals(Token.IDENTIFIER, tokenType(area, "changed"));
            assertEquals(Token.FUNCTION, tokenType(area, "later"));
            return null;
        });
    }

    @Test
    void freshSemanticResultCanRemoveAnOldMethodColor() throws Exception {
        onEdt(() -> {
            RSyntaxTextArea area = new RSyntaxTextArea();
            CustomJavaTokenMaker tokenMaker = new CustomJavaTokenMaker();
            ((RSyntaxDocument) area.getDocument()).setSyntaxStyle(tokenMaker);
            area.setText("doWork();");
            tokenMaker.setSemanticTokenTypes(Map.of(0, Token.FUNCTION), area);
            assertEquals(Token.FUNCTION, tokenType(area, "doWork"));

            tokenMaker.setSemanticTokenTypes(Map.of(), area);
            assertEquals(Token.IDENTIFIER, tokenType(area, "doWork"));
            return null;
        });
    }

    @Test
    void colorsContextualKeywordsUsedAsMethodAndFieldNames() throws Exception {
        onEdt(() -> {
            RSyntaxTextArea area = new RSyntaxTextArea();
            CustomJavaTokenMaker tokenMaker = new CustomJavaTokenMaker();
            ((RSyntaxDocument) area.getDocument()).setSyntaxStyle(tokenMaker);
            String text = "receiver.open(); receiver.yield(); receiver.module;";
            area.setText(text);
            tokenMaker.setSemanticTokenTypes(Map.of(
                    text.indexOf("open"), Token.FUNCTION,
                    text.indexOf("yield"), Token.FUNCTION,
                    text.indexOf("module"), Token.VARIABLE
            ), area);

            assertEquals(Token.FUNCTION, tokenType(area, "open"));
            assertEquals(Token.FUNCTION, tokenType(area, "yield"));
            assertEquals(Token.VARIABLE, tokenType(area, "module"));
            return null;
        });
    }

    @Test
    void colorsQualifiedBuiltInTypesThatTheLexerCombines() throws Exception {
        onEdt(() -> {
            RSyntaxTextArea area = new RSyntaxTextArea();
            CustomJavaTokenMaker tokenMaker = new CustomJavaTokenMaker();
            ((RSyntaxDocument) area.getDocument()).setSyntaxStyle(tokenMaker);
            String text = "Thread.State state; Map.Entry entry;";
            area.setText(text);
            tokenMaker.setSemanticTokenTypes(Map.of(
                    text.indexOf("Thread"), ShadowedTokenTypes.TYPE,
                    text.indexOf("State"), ShadowedTokenTypes.TYPE,
                    text.indexOf("Map"), ShadowedTokenTypes.TYPE,
                    text.indexOf("Entry"), ShadowedTokenTypes.TYPE
            ), area);

            assertEquals(ShadowedTokenTypes.TYPE, tokenType(area, "Thread.State"));
            assertEquals(ShadowedTokenTypes.TYPE, tokenType(area, "Map.Entry"));
            area.insert("\n", 0);
            assertEquals(ShadowedTokenTypes.TYPE, tokenType(area, "Thread.State"));
            assertEquals(ShadowedTokenTypes.TYPE, tokenType(area, "Map.Entry"));
            return null;
        });
    }

    private static RSyntaxTextArea editor(String text) {
        RSyntaxTextArea area = new RSyntaxTextArea();
        CustomJavaTokenMaker tokenMaker = new CustomJavaTokenMaker();
        ((RSyntaxDocument) area.getDocument()).setSyntaxStyle(tokenMaker);
        area.setText(text);
        tokenMaker.setSemanticTokenTypes(Map.of(
                text.indexOf("doWork"), Token.FUNCTION,
                text.indexOf("later"), Token.FUNCTION
        ), area);
        return area;
    }

    private static int tokenType(RSyntaxTextArea area, String lexeme) {
        int offset = area.getText().indexOf(lexeme);
        RSyntaxDocument document = (RSyntaxDocument) area.getDocument();
        int line = document.getDefaultRootElement().getElementIndex(offset);
        for (Token token = document.getTokenListForLine(line); token != null; token = token.getNextToken()) {
            if (token.getOffset() == offset && lexeme.equals(token.getLexeme())) {
                return token.getType();
            }
        }
        return fail("No token for " + lexeme + " at " + offset);
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
