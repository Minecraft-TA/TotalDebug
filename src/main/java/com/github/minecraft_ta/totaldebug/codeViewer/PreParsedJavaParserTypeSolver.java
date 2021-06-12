package com.github.minecraft_ta.totaldebug.codeViewer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.model.resolution.SymbolReference;
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class PreParsedJavaParserTypeSolver implements TypeSolver {

    private TypeSolver parent;

    private final Map<String, TypeDeclaration<?>> knownTypes = new HashMap<>();

    public PreParsedJavaParserTypeSolver() {}

    public PreParsedJavaParserTypeSolver(CompilationUnit... units) {
        this(Arrays.asList(units));
    }

    public PreParsedJavaParserTypeSolver(Iterable<CompilationUnit> units) {
        units.forEach(this::addCompilationUnit);
    }

    public void addCompilationUnit(CompilationUnit unit) {
        for (TypeDeclaration<?> type : unit.getTypes()) {
            knownTypes.put(type.getFullyQualifiedName().orElseThrow(RuntimeException::new), type);
        }
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        TypeDeclaration<?> typeDeclaration = this.knownTypes.get(name);
        if (typeDeclaration == null)
            return SymbolReference.unsolved(ResolvedReferenceTypeDeclaration.class);

        return SymbolReference.solved(JavaParserFacade.get(this).getTypeDeclaration(typeDeclaration));
    }

    @Override
    public TypeSolver getParent() {
        return parent;
    }

    @Override
    public void setParent(TypeSolver parent) {
        Objects.requireNonNull(parent);
        if (this.parent != null) {
            throw new IllegalStateException("This TypeSolver already has a parent.");
        }
        if (parent == this) {
            throw new IllegalStateException("The parent of this TypeSolver cannot be itself.");
        }
        this.parent = parent;
    }
}
