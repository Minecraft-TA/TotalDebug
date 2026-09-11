package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaAst;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.util.Objects;
import java.util.Optional;

/** Resolves displayed source lines identically for the editor and remote debugger callers. */
public final class DebuggerBreakpointResolver {
    private DebuggerBreakpointResolver() {
    }

    public static Optional<DebugEngine.SourceBreakpoint> resolve(
            DebugEngine.Source source, int line, String condition, String hitCondition
    ) {
        return resolve(source, JavaAst.parse("DebuggerBreakpoint", source.contents()),
                line, condition, hitCondition);
    }

    public static Optional<DebugEngine.SourceBreakpoint> resolve(
            DebugEngine.Source source, CompilationUnit unit, int line, String condition, String hitCondition
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(unit, "unit");
        if (line < 1 || line > source.contents().lines().count()) {
            throw new IllegalArgumentException("Source has no displayed line " + line);
        }
        if (source.lineMap().isEmpty()) {
            return Optional.of(new DebugEngine.SourceBreakpoint(line, condition, hitCondition));
        }
        MethodDeclaration[] selected = new MethodDeclaration[1];
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration declaration) {
                if (selected[0] == null && unit.getLineNumber(declaration.getName().getStartPosition()) == line) {
                    selected[0] = declaration;
                }
                return true;
            }
        });
        MethodDeclaration declaration = selected[0];
        if (declaration == null) {
            if (!source.lineMap().containsDisplayedLine(line)) {
                return Optional.empty();
            }
            return Optional.of(new DebugEngine.SourceBreakpoint(line, condition, hitCondition));
        }
        int endLine = unit.getLineNumber(declaration.getStartPosition() + declaration.getLength() - 1);
        var debuggerLine = source.lineMap().firstMappedDisplayedLine(line, endLine);
        if (debuggerLine.isEmpty()) return Optional.empty();
        var binding = declaration.resolveBinding();
        if (binding == null || !(JavaSymbolResolver.trySymbolForBinding(binding) instanceof CodeSymbol.MethodSymbol method)) {
            throw new IllegalArgumentException("Cannot resolve the runtime method declared at line " + line);
        }
        return Optional.of(DebugEngine.SourceBreakpoint.methodEntry(line, debuggerLine.getAsInt(),
                new DebugEngine.MethodTarget(method.ownerClassName(), method.name(), method.descriptor()),
                condition, hitCondition));
    }
}
