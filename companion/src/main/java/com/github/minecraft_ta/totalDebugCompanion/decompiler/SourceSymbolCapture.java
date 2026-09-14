package com.github.minecraft_ta.totalDebugCompanion.decompiler;

import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceDocument;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.extern.TextTokenVisitor;
import org.jetbrains.java.decompiler.struct.gen.FieldDescriptor;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.util.token.TextRange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A per-decompilation collector, supplied through Vineflower's context rather than global mutable state. */
public final class SourceSymbolCapture {
    public static final String CONTEXT_KEY = "totaldebug.source-symbol-capture";
    private final Map<String, List<SourceDocument.SymbolSpan>> byContents = new HashMap<>();

    public static void registerVisitor() {
        Object value = DecompilerContext.getProperty(CONTEXT_KEY);
        if (value instanceof SourceSymbolCapture capture) TextTokenVisitor.addVisitor(capture::visitor);
    }

    synchronized List<SourceDocument.SymbolSpan> forSource(String source) {
        List<SourceDocument.SymbolSpan> spans = this.byContents.get(source);
        if (spans == null) throw new IllegalStateException("Vineflower did not capture the emitted source");
        return spans;
    }

    private TextTokenVisitor visitor(TextTokenVisitor next) {
        return new TextTokenVisitor(next) {
            private String contents;
            private final List<SourceDocument.SymbolSpan> spans = new ArrayList<>();

            @Override public void start(String contents) {
                super.start(contents);
                this.contents = contents;
                this.spans.clear();
            }

            @Override public void visitClass(TextRange range, boolean declaration, String name) {
                super.visitClass(range, declaration, name);
                add(range, declaration, new CodeSymbol.ClassSymbol(name.replace('/', '.')));
            }

            @Override public void visitField(TextRange range, boolean declaration, String owner, String name, FieldDescriptor descriptor) {
                super.visitField(range, declaration, owner, name, descriptor);
                add(range, declaration, new CodeSymbol.FieldSymbol(owner.replace('/', '.'), name, descriptor.descriptorString));
            }

            @Override public void visitMethod(TextRange range, boolean declaration, String owner, String name, MethodDescriptor descriptor) {
                super.visitMethod(range, declaration, owner, name, descriptor);
                add(range, declaration, new CodeSymbol.MethodSymbol(owner.replace('/', '.'), name, descriptor.toString()));
            }

            @Override public void visitParameter(TextRange range, boolean declaration, String owner, String method,
                                                 MethodDescriptor descriptor, int index, String name) {
                super.visitParameter(range, declaration, owner, method, descriptor, index, name);
                if (declaration && range.length > 0) {
                    this.spans.add(new SourceDocument.SymbolSpan(
                            new CodeSymbol.MethodSymbol(owner.replace('/', '.'), method, descriptor.toString()),
                            SourceDocument.SymbolRole.METHOD_PARAMETER, range.start, range.length));
                }
            }

            @Override public void visitLocal(TextRange range, boolean declaration, String owner, String method,
                                             MethodDescriptor descriptor, int index, String name) {
                super.visitLocal(range, declaration, owner, method, descriptor, index, name);
                // References to captured variables report their original declaration's method.
                // Only declarations establish the lambda that owns this source scope.
                if (declaration && range.length > 0) {
                    this.spans.add(new SourceDocument.SymbolSpan(
                            new CodeSymbol.MethodSymbol(owner.replace('/', '.'), method, descriptor.toString()),
                            SourceDocument.SymbolRole.METHOD_LOCAL, range.start, range.length));
                }
            }

            private void add(TextRange range, boolean declaration, CodeSymbol symbol) {
                if (range.length > 0) this.spans.add(new SourceDocument.SymbolSpan(symbol,
                        declaration ? SourceDocument.SymbolRole.DECLARATION : SourceDocument.SymbolRole.REFERENCE,
                        range.start, range.length));
            }

            @Override public void end() {
                super.end();
                synchronized (SourceSymbolCapture.this) {
                    byContents.put(this.contents, List.copyOf(this.spans));
                }
            }
        };
    }
}
