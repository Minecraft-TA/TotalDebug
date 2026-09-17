package com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting;

import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rsyntaxtextarea.modes.JavaTokenMaker;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Segment;
import javax.swing.text.TextAction;
import java.awt.event.ActionEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

public class CustomJavaTokenMaker extends JavaTokenMaker {

    private static final char[] VAR_CHAR_ARRAY = {'v', 'a', 'r'};

    private final DocumentChangeListener documentListener = this::documentChanged;
    private RSyntaxDocument document;
    private Map<Integer, SemanticToken> overwrittenTokenTypes;

    public static CustomJavaTokenMaker forEditor(RSyntaxTextArea textArea) {
        try {
            var field = RSyntaxDocument.class.getDeclaredField("tokenMaker");
            field.setAccessible(true);
            return (CustomJavaTokenMaker) field.get(textArea.getDocument());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot access the Java token maker", failure);
        }
    }

    /** Full editors forward edits from their analysis owner; expression fields track their own document. */
    public void managedByEditor(RSyntaxTextArea textArea) {
        trackDocument(textArea);
        document.removeDocumentListener(documentListener);
    }

    public void setSemanticTokenTypes(Map<Integer, Integer> tokenTypes, RSyntaxTextArea textArea) {
        setSemanticTokenTypes(tokenTypes, textArea, ignored -> false);
    }

    public void setSemanticTokenTypes(Map<Integer, Integer> tokenTypes, RSyntaxTextArea textArea, IntPredicate retain) {
        trackDocument(textArea);
        var tokens = new HashMap<Integer, SemanticToken>();
        // Use the lexer's spans; it combines some qualified names, such as Thread.State.
        for (Token token : this.document) {
            Integer type = tokenTypes.get(token.getOffset());
            if (type == null && overwrittenTokenTypes != null && retain.test(token.getOffset())) {
                var previous = overwrittenTokenTypes.get(token.getOffset());
                if (previous != null && previous.text().equals(token.getLexeme())) type = previous.type();
            }
            if (type != null && token.isPaintable()) {
                tokens.put(token.getOffset(), new SemanticToken(token.getLexeme(), type));
            }
        }
        this.overwrittenTokenTypes = tokens;
        invalidateTokenCache(this.document);
        textArea.repaint();
    }

    private void trackDocument(RSyntaxTextArea textArea) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Semantic highlighting must be updated on the event dispatch thread");
        }
        RSyntaxDocument current = (RSyntaxDocument) textArea.getDocument();
        if (this.document != current) {
            if (this.document != null) {
                this.document.removeDocumentListener(this.documentListener);
            }
            this.document = current;
            this.overwrittenTokenTypes = null;
            current.addDocumentListener(this.documentListener);
        }
    }

    public void documentChanged(DocumentEvent event) {
        if (event.getType() == DocumentEvent.EventType.CHANGE || this.overwrittenTokenTypes == null) {
            return;
        }
        int offset = event.getOffset();
        int length = event.getLength();
        boolean insertion = event.getType() == DocumentEvent.EventType.INSERT;
        var shifted = new HashMap<Integer, SemanticToken>();
        // Keep untouched names aligned while the asynchronous parse catches up.
        this.overwrittenTokenTypes.forEach((start, token) -> {
            int end = start + token.text().length();
            if (insertion) {
                if (offset <= start) {
                    shifted.put(start + length, token);
                } else if (offset >= end) {
                    shifted.put(start, token);
                }
            } else if (end <= offset) {
                shifted.put(start, token);
            } else if (start >= offset + length) {
                shifted.put(start - length, token);
            }
        });
        this.overwrittenTokenTypes = shifted;
        invalidateTokenCache(this.document);
    }

    private static void invalidateTokenCache(RSyntaxDocument document) {
        try {
            var field = RSyntaxDocument.class.getDeclaredField("lastLine");
            field.setAccessible(true);
            field.setInt(document, -1);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to invalidate RSyntaxDocument's token cache", exception);
        }
    }

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        var firstToken = super.getTokenList(text, initialTokenType, startOffset);
        var currentToken = firstToken;

        if (this.overwrittenTokenTypes == null)
            return firstToken;

        while (currentToken != null) {
            //Exclude "var" from being highlighted
            if (currentToken.getType() == TokenTypes.DATA_TYPE && CharOperation.equals(VAR_CHAR_ARRAY, currentToken.getTextArray(), currentToken.getTextOffset(), currentToken.getTextOffset() + currentToken.length())) {
                currentToken.setType(TokenTypes.IDENTIFIER);
            } else if (currentToken.getType() == TokenTypes.IDENTIFIER
                    || currentToken.getType() == TokenTypes.FUNCTION
                    || currentToken.getType() == TokenTypes.RESERVED_WORD
                    || currentToken.getType() == TokenTypes.RESERVED_WORD_2) {
                var token = this.overwrittenTokenTypes.get(currentToken.getOffset());
                if (token != null && token.text().equals(currentToken.getLexeme()))
                    currentToken.setType(token.type());
            }

            currentToken = currentToken.getNextToken();
        }

        return firstToken;
    }

    private record SemanticToken(String text, int type) {
    }

    @Override
    public Action getInsertBreakAction() {
        return new TextAction("customInsertBreak") {

            @Override
            public void actionPerformed(ActionEvent e) {
                var area = (RSyntaxTextArea) getTextComponent(e);
                var document = (RSyntaxDocument) area.getDocument();

                var caretPos = area.getCaretPosition();
                var root = document.getDefaultRootElement();
                var line = root.getElement(root.getElementIndex(caretPos));
                var start = line.getStartOffset();
                var len = line.getEndOffset() - 1 - start;
                try {
                    var lineText = document.getText(start, len);
                    var tabCount = getTabCount(lineText);
                    var builder = new StringBuilder("\n");
                    builder.append("\t".repeat(tabCount + (caretPos > 0 && document.getText(caretPos - 1, 1).equals("{") ? 1 : 0)));

                    caretPos += builder.length();
                    if (getOpenBraceCount(document) > 0)
                        builder.append("\n").append("\t".repeat(tabCount)).append("}");

                    area.insert(builder.toString(), area.getCaretPosition());
                    area.setCaretPosition(caretPos);
                } catch (BadLocationException ex) {
                    ex.printStackTrace();
                }
            }

            private static int getTabCount(String t) {
                var c = 0;
                for (var i = 0; i < t.length(); i++) {
                    if (t.charAt(i) == '\t')
                        c++;
                    else
                        return c;
                }

                return c;
            }

            private static int getOpenBraceCount(RSyntaxDocument doc) {
                int openCount = 0;
                for (Token t : doc) {
                    if (t.getType() == Token.SEPARATOR && t.length() == 1) {
                        char ch = t.charAt(0);
                        if (ch == '{')
                            openCount++;
                        else if (ch == '}')
                            openCount--;
                    }
                }

                return openCount;
            }
        };
    }
}
