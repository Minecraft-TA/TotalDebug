package com.github.minecraft_ta.totalDebugCompanion.jdt.insight;

import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Extracts exact JVM declarations and stable paint anchors from a parsed source file. */
public final class SourceDeclarationAnalyzer {
    private SourceDeclarationAnalyzer() {
    }

    public static List<SourceDeclaration> analyze(CompilationUnit unit, String source) {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(source, "source");
        ArrayList<SourceDeclaration> declarations = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration declaration) {
                add(declaration.resolveBinding(), declaration.getName(), typeAnchor(declaration.getName(), declaration));
                return true;
            }

            @Override
            public boolean visit(EnumDeclaration declaration) {
                add(declaration.resolveBinding(), declaration.getName(), typeAnchor(declaration.getName(), declaration));
                return true;
            }

            @Override
            public boolean visit(AnnotationTypeDeclaration declaration) {
                add(declaration.resolveBinding(), declaration.getName(), typeAnchor(declaration.getName(), declaration));
                return true;
            }

            @Override
            public boolean visit(RecordDeclaration declaration) {
                add(declaration.resolveBinding(), declaration.getName(), typeAnchor(declaration.getName(), declaration));
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration declaration) {
                int anchor = declaration.getBody() == null
                        ? declaration.getStartPosition() + declaration.getLength()
                        : declaration.getBody().getStartPosition() + 1;
                add(declaration.resolveBinding(), declaration.getName(), anchor);
                return true;
            }

            @Override
            public boolean visit(FieldDeclaration declaration) {
                int declarationAnchor = declaration.getStartPosition() + declaration.getLength();
                for (Object candidate : declaration.fragments()) {
                    VariableDeclarationFragment fragment = (VariableDeclarationFragment) candidate;
                    int anchor = declaration.fragments().size() == 1
                            ? declarationAnchor
                            : fragment.getName().getStartPosition() + fragment.getName().getLength();
                    add(fragment.resolveBinding(), fragment.getName(), anchor);
                }
                return false;
            }

            @Override
            public boolean visit(EnumConstantDeclaration declaration) {
                add(
                        declaration.resolveVariable(),
                        declaration.getName(),
                        declaration.getStartPosition() + declaration.getLength()
                );
                return true;
            }

            private int typeAnchor(SimpleName name, org.eclipse.jdt.core.dom.ASTNode declaration) {
                int searchStart = name.getStartPosition() + name.getLength();
                int searchEnd = Math.min(source.length(), declaration.getStartPosition() + declaration.getLength());
                int brace = source.indexOf('{', searchStart);
                return brace >= 0 && brace < searchEnd ? brace + 1 : searchStart;
            }

            private void add(org.eclipse.jdt.core.dom.IBinding binding, SimpleName name, int anchor) {
                CodeSymbol symbol = JavaSymbolResolver.trySymbolForBinding(binding);
                if (symbol == null || anchor < 0 || anchor > source.length()) {
                    return;
                }
                declarations.add(new SourceDeclaration(symbol, anchor, name.getStartPosition()));
            }
        });
        declarations.sort(Comparator.comparingInt(SourceDeclaration::anchorOffset));
        return List.copyOf(declarations);
    }
}
