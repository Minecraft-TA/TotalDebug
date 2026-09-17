package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.CustomJavaParser;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaEditorSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import javax.swing.SwingUtilities;
import javax.swing.event.CaretListener;
import javax.swing.event.CaretEvent;
import javax.swing.event.DocumentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** Owns analysis admission, freshness and presentation for one Java document. All state lives on the EDT. */
final class JavaEditorAnalysis implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(JavaEditorAnalysis.class.getName());
    private final RSyntaxTextArea editor;
    private final CustomJavaTokenMaker tokens;
    private final CustomJavaParser parser;
    private final ASTCache.Registration registration;
    private final Executor executor;
    private final Function<Request, JavaAnalysis> analyze;
    private final JavaEditingRegion editing = new JavaEditingRegion();
    private final JavaImportWarnings importWarnings;
    private boolean scheduled;
    private boolean initialized;
    private final DocumentChangeListener documentListener = this::edited;
    private final CaretListener caretListener = this::caretChanged;
    private List<JavaAnalysis.Problem> displayed = List.of();
    private JavaAnalysis current;
    private long revision;
    private Object environment = CompanionClassIndex.identity();
    private boolean editTurn;
    private boolean running;
    private boolean requested;
    private boolean closed;

    record Request(long revision, Object environment, String text) { }

    JavaEditorAnalysis(RSyntaxTextArea editor, ASTCache cache, String key, String className,
                       Function<String, JavaEditorSource> sourceFactory, Executor executor, boolean diagnostics) {
        this(editor, cache, key, executor, diagnostics,
                request -> JavaAnalysis.parse(className, request.text(), sourceFactory.apply(request.text()), request.revision(), request.environment()));
    }

    /** A controlled executor lets tests drive the same lifecycle without an application window. */
    JavaEditorAnalysis(RSyntaxTextArea editor, ASTCache cache, String key, Executor executor,
                       boolean diagnostics, Function<Request, JavaAnalysis> analyze) {
        this.editor = editor;
        this.importWarnings = new JavaImportWarnings(editor.getText());
        this.executor = executor;
        this.analyze = analyze;
        this.tokens = CustomJavaTokenMaker.forEditor(editor);
        tokens.managedByEditor(editor);
        this.parser = diagnostics ? new CustomJavaParser() : null;
        if (parser != null) {
            editor.setParserDelay(Integer.MAX_VALUE);
            editor.addParser(parser);
        }
        this.registration = cache.register(key, this::environmentChanged);
        editor.getDocument().addDocumentListener(documentListener);
        editor.addCaretListener(caretListener);
        // Subclasses set their initial contents in the same EDT turn.
        schedule();
    }

    JavaAnalysis currentSnapshot() {
        return current != null && current.revision() == revision && current.environment() == CompanionClassIndex.identity()
                && registration.isOpen() ? current : null;
    }

    private void caretChanged(CaretEvent event) {
        if (!editTurn && editing.caretMoved(event.getDot())) {
            finishEditing();
        }
    }

    private void edited(DocumentEvent event) {
        if (closed || event.getType() == DocumentEvent.EventType.CHANGE) return;
        int offset = event.getOffset();
        int removed = event.getType() == DocumentEvent.EventType.REMOVE ? event.getLength() : 0;
        int added = event.getType() == DocumentEvent.EventType.INSERT ? event.getLength() : 0;
        String text = editor.getText();
        importWarnings.edited(text, offset, removed, added);
        editTurn = true;
        SwingUtilities.invokeLater(() -> editTurn = false);
        revision++;
        current = null;
        registration.publish(null);
        tokens.documentChanged(event);
        var retained = new ArrayList<JavaAnalysis.Problem>();
        for (var problem : displayed) {
            var shifted = problem.span().edited(offset, removed, added);
            // A provisional error belongs to the previous unfinished source. Completing the edit must not reveal it.
            if (shifted != null && problem.unusedImport() == null && !editing.hides(problem)) retained.add(problem.at(shifted));
        }
        retained.addAll(importWarnings.current((RSyntaxDocument) editor.getDocument()));
        displayed = retained;
        if (initialized) editing.edited((RSyntaxDocument) editor.getDocument(), text, offset, removed, added);
        paintProblems();
        requested = false;
        schedule();
    }

    private void schedule() {
        if (scheduled || closed) return;
        scheduled = true;
        SwingUtilities.invokeLater(() -> {
            scheduled = false;
            initialized = true;
            requestNow();
        });
    }

    void finishEditing() {
        editing.finish();
        if (currentSnapshot() != null) showProblems(current);
        else paintProblems();
        requestNow();
    }

    void completionAccepted() {
        editing.completionAccepted((RSyntaxDocument) editor.getDocument(), editor.getText(), editor.getCaretPosition());
        paintProblems();
        schedule();
    }

    void requestNow() {
        if (closed || !registration.isOpen()) return;
        if (currentSnapshot() != null) return;
        if (running) { requested = true; return; }
        var request = new Request(revision, CompanionClassIndex.identity(), editor.getText());
        running = true;
        requested = false;
        try {
            executor.execute(() -> {
                JavaAnalysis result = null;
                Throwable failure = null;
                try {
                    if (registration.isOpen() && request.environment() == CompanionClassIndex.identity()) result = analyze.apply(request);
                }
                catch (Exception exception) { failure = exception; }
                JavaAnalysis completed = result;
                Throwable error = failure;
                SwingUtilities.invokeLater(() -> completed(request, completed, error));
            });
        } catch (RuntimeException rejection) { completed(request, null, rejection); }
    }

    private void completed(Request request, JavaAnalysis result, Throwable failure) {
        running = false;
        if (closed || !registration.isOpen()) return;
        if (request.revision() == revision && request.environment() == CompanionClassIndex.identity()) {
            if (failure != null) {
                LOGGER.log(System.Logger.Level.WARNING, "Java editor analysis failed", failure);
            } else {
                current = result;
                importWarnings.accept(result, (RSyntaxDocument) editor.getDocument());
                editing.analyzed(result);
                tokens.setSemanticTokenTypes(result.tokens(), editor,
                        offset -> result.recovery().stream().anyMatch(span -> span.contains(offset)));
                showProblems(result);
                registration.publish(result);
            }
        }
        if (requested) requestNow();
    }

    private void paintProblems() {
        if (parser == null) return;
        parser.setProblems(displayed.stream().filter(problem -> !editing.hides(problem)).toList());
        editor.forceReparsing(parser);
    }

    private void showProblems(JavaAnalysis result) {
        var next = new ArrayList<>(editing.reconcile(result, displayed));
        if (!result.importsChecked()) {
            next.removeIf(problem -> problem.unusedImport() != null);
            next.addAll(importWarnings.current((RSyntaxDocument) editor.getDocument()));
        }
        displayed = next;
        paintProblems();
    }

    private void environmentChanged() {
        if (closed || environment == CompanionClassIndex.identity()) return;
        environment = CompanionClassIndex.identity();
        revision++;
        current = null;
        registration.publish(null);
        displayed = List.of();
        importWarnings.clear();
        tokens.setSemanticTokenTypes(Map.of(), editor);
        paintProblems();
        requestNow();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        editor.getDocument().removeDocumentListener(documentListener);
        editor.removeCaretListener(caretListener);
        if (parser != null) editor.removeParser(parser);
        registration.close();
    }
}
