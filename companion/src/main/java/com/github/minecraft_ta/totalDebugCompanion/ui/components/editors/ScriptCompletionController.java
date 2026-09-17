package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItem;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CustomCompletionRequestor;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CustomTextEdit;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.Range;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.SnippetCompletionAdapter;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.CodeCompletionPopup;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.JavaModelException;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.AbstractAction;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.text.BadLocationException;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** One EDT owner for completion requests, popup interaction and atomic source edits. */
final class ScriptCompletionController implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(ScriptCompletionController.class.getName());
    private final RSyntaxTextArea editor;
    private final String className;
    private final CodeCompletionPopup popup;
    private final Executor executor;
    private final Function<Request, List<CompletionItem>> compute;
    private final Runnable accepted;
    private final SnippetCompletionAdapter snippets;
    private final DocumentChangeListener documentListener = this::edited;
    private final CaretListener caretListener = event -> {
        if (!this.applying && !this.documentEdit) dismiss();
    };
    private final FocusAdapter focusListener = new FocusAdapter() {
        @Override public void focusLost(FocusEvent event) { dismiss(); }
    };
    private final KeyAdapter keyListener = new KeyAdapter() {
        @Override public void keyPressed(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.VK_ESCAPE && (active != null || scheduled || popup.isVisible())) {
                dismiss();
                event.consume();
            }
        }
    };
    private Request active;
    private String queuedSelection;
    private boolean scheduled;
    private boolean documentEdit;
    private boolean applying;
    private boolean closed;

    ScriptCompletionController(RSyntaxTextArea editor, String className, CodeCompletionPopup popup,
                               Executor executor, Runnable accepted) {
        this(editor, className, popup, executor, accepted, Request::compute);
    }

    ScriptCompletionController(RSyntaxTextArea editor, String className, CodeCompletionPopup popup,
                               Executor executor, Runnable accepted, Function<Request, List<CompletionItem>> compute) {
        this.editor = editor;
        this.className = className;
        this.popup = popup;
        this.executor = executor;
        this.accepted = accepted;
        this.compute = compute;
        this.snippets = new SnippetCompletionAdapter(editor);
        popup.setDismissListener(this::invalidate);
        editor.getDocument().addDocumentListener(documentListener);
        editor.addCaretListener(caretListener);
        editor.addFocusListener(focusListener);
        editor.addKeyListener(keyListener);
        editor.getInputMap().put(KeyStroke.getKeyStroke("ctrl SPACE"), "autoComplete");
        editor.getActionMap().put("autoComplete", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { request(); }
        });
    }

    private void edited(DocumentEvent event) {
        if (applying || closed || event.getType() == DocumentEvent.EventType.CHANGE) return;
        documentEdit = true;
        SwingUtilities.invokeLater(() -> documentEdit = false);
        cancelRequest();
        queuedSelection = null;
        if (event.getType() != DocumentEvent.EventType.INSERT || event.getLength() != 1 || !editor.isFocusOwner()) {
            dismiss();
            return;
        }
        char inserted = editor.getText().charAt(event.getOffset());
        if (!Character.isJavaIdentifierPart(inserted) && inserted != '.') { dismiss(); return; }
        if (scheduled) return;
        scheduled = true;
        SwingUtilities.invokeLater(() -> {
            if (!scheduled || closed) return;
            scheduled = false;
            startRequest();
        });
    }

    void request() {
        scheduled = false;
        queuedSelection = null;
        startRequest();
    }

    private void startRequest() {
        if (closed) return;
        cancelRequest();
        try {
            var request = new Request(className, editor.getText(), editor.getCaretPosition());
            active = request;
            executor.execute(() -> {
                List<CompletionItem> items = List.of();
                Exception failure = null;
                try {
                    if (!request.requestor.isCanceled()) items = compute.apply(request);
                } catch (Exception error) { failure = error; }
                var result = items;
                var error = failure;
                SwingUtilities.invokeLater(() -> completed(request, result, error));
            });
        } catch (RuntimeException failure) { failed(failure); }
    }

    private boolean current(Request request) {
        return !closed && active == request && !request.requestor.isCanceled()
                && request.environment == CompanionClassIndex.identity()
                && request.caret == editor.getCaretPosition() && request.source.editorText().equals(editor.getText());
    }

    private void completed(Request request, List<CompletionItem> result, Exception failure) {
        if (closed || active != request) return;
        if (!current(request) || !editor.isFocusOwner()) { dismiss(); return; }
        if (failure != null) { failed(failure); return; }
        try {
            var items = new ArrayList<>(result);
            items.removeIf(item -> !mapEdits(item, request.source));
            if (items.isEmpty()) { dismiss(); return; }
            if (queuedSelection != null) {
                var chosen = items.stream().filter(item -> item.getIdentity().equals(queuedSelection)).findFirst().orElse(items.getFirst());
                queuedSelection = null;
                accept(chosen);
                return;
            }
            popup.setKeyEnterListener(this::accept);
            var token = request.requestor.getContext().getToken();
            popup.setToken(token == null ? "" : new String(token));
            popup.setItems(items);
            popup.show(editor);
        } catch (RuntimeException error) { failed(error); }
    }

    private void failed(Exception failure) {
        if (!(failure instanceof OperationCanceledException) && !(failure.getCause() instanceof OperationCanceledException))
            LOGGER.log(System.Logger.Level.WARNING, "Unable to complete script " + className, failure);
        dismiss();
    }

    private void accept(CompletionItem item) {
        if (active == null || item.getRequestor() != active.requestor) {
            if (scheduled || active != null && current(active)) queuedSelection = item.getIdentity();
            else dismiss();
            return;
        }
        if (!current(active)) { dismiss(); return; }
        applyEdits(item.getTextEdits());
        accepted.run();
    }

    /** Formatting uses the same snippet-aware atomic edit path as completion. */
    void applyEdits(List<CustomTextEdit> textEdits) {
        applying = true;
        editor.beginAtomicEdit();
        try {
            var edits = textEdits.stream().filter(CustomTextEdit::isSnippet).toArray(CustomTextEdit[]::new);
            if (edits.length != 0) snippets.insert(edits);
            for (var edit : textEdits) if (!edit.isSnippet()) apply(edit);
        } finally {
            editor.endAtomicEdit();
            applying = false;
            dismiss();
        }
    }

    private void apply(CustomTextEdit edit) {
        snippets.beginIgnoredDocumentChange();
        try {
            ((RSyntaxDocument) editor.getDocument()).replace(edit.getRange().getOffset(), edit.getRange().getLength(), edit.getNewText(), null);
        } catch (BadLocationException failure) {
            throw new IllegalStateException("Completion range no longer belongs to its source", failure);
        } finally { snippets.endIgnoredDocumentChange(); }
    }

    static boolean mapEdits(CompletionItem item, JavaSnippetSource.GeneratedSource source) {
        item.getTextEdits().removeIf(edit -> {
            Range range = edit.getRange();
            int generatedStart = range.getOffset();
            int start = source.sourceMap().toEditorOffset(generatedStart);
            int end = source.sourceMap().toEditorOffset(range.getEndOffset());
            if (start < 0 || end < start) return true;
            if (range.getLength() == 0) edit.setNewText(source.sourceMap().mapInsertionText(generatedStart, edit.getNewText()));
            range.setOffset(start);
            range.setLength(end - start);
            return false;
        });
        return !item.getTextEdits().isEmpty();
    }

    private void cancelRequest() {
        if (active != null) active.requestor.setCanceled(true);
        active = null;
    }

    private void invalidate() {
        scheduled = false;
        queuedSelection = null;
        cancelRequest();
    }

    private void dismiss() {
        invalidate();
        popup.setVisible(false);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        dismiss();
        editor.getDocument().removeDocumentListener(documentListener);
        editor.removeCaretListener(caretListener);
        editor.removeFocusListener(focusListener);
        editor.removeKeyListener(keyListener);
        snippets.close();
        popup.setDismissListener(null);
        popup.dispose();
    }

    /** The worker and its result share the same source, caret, environment and cancellation handle. */
    static final class Request {
        final JavaSnippetSource.GeneratedSource source;
        final int caret;
        final Object environment = CompanionClassIndex.identity();
        final CustomCompletionRequestor requestor;
        private final CompilationUnitImpl unit;
        private final int offset;
        private List<CompletionItem> result = List.of();

        Request(String className, String text, int caret) {
            this.source = JavaSnippetSource.body(className, text);
            this.caret = caret;
            this.unit = new CompilationUnitImpl(className, source.source());
            this.offset = source.sourceMap().toGeneratedOffset(caret);
            this.requestor = new CustomCompletionRequestor(unit, offset, (ignored, items) -> result = items);
        }

        List<CompletionItem> compute() {
            try { unit.codeComplete(offset, requestor, requestor); }
            catch (JavaModelException failure) { throw new IllegalStateException("Completion failed", failure); }
            return result;
        }
    }
}
