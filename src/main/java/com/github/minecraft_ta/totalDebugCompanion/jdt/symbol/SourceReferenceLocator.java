package com.github.minecraft_ta.totalDebugCompanion.jdt.symbol;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.VariableDeclaration;

import java.util.Objects;
import java.util.OptionalInt;

/** Finds the first rendered source occurrence of an exact indexed JVM symbol. */
public final class SourceReferenceLocator {
    private SourceReferenceLocator() {
    }

    public static OptionalInt findFirst(ASTNode declarationSite, ReferenceQuery query) {
        Objects.requireNonNull(declarationSite, "declarationSite");
        Objects.requireNonNull(query, "query");

        MatchVisitor visitor = new MatchVisitor(declarationSite, query);
        declarationSite.accept(visitor);
        return visitor.offset < 0 ? OptionalInt.empty() : OptionalInt.of(visitor.offset);
    }

    private static final class MatchVisitor extends ASTVisitor {
        private final ASTNode declarationSite;
        private final ReferenceQuery query;
        private int offset = -1;

        private MatchVisitor(ASTNode declarationSite, ReferenceQuery query) {
            this.declarationSite = declarationSite;
            this.query = query;
        }

        @Override
        public boolean preVisit2(ASTNode node) {
            if (this.offset >= 0) {
                return false;
            }
            if (node != this.declarationSite
                    && (node instanceof AbstractTypeDeclaration
                    || node instanceof org.eclipse.jdt.core.dom.AnonymousClassDeclaration)) {
                return false;
            }
            return !(this.declarationSite instanceof AbstractTypeDeclaration
                    && node != this.declarationSite
                    && node instanceof BodyDeclaration);
        }

        @Override
        public boolean visit(SimpleName name) {
            if (!isDeclarationName(name) && matches(name.resolveBinding())) {
                this.offset = name.getStartPosition();
            }
            return this.offset < 0;
        }

        @Override
        public boolean visit(ClassInstanceCreation creation) {
            if (matches(creation.resolveConstructorBinding())) {
                this.offset = creation.getType().getStartPosition();
                return false;
            }
            return true;
        }

        @Override
        public boolean visit(ConstructorInvocation invocation) {
            if (matches(invocation.resolveConstructorBinding())) {
                this.offset = invocation.getStartPosition();
                return false;
            }
            return true;
        }

        @Override
        public boolean visit(SuperConstructorInvocation invocation) {
            if (matches(invocation.resolveConstructorBinding())) {
                this.offset = invocation.getStartPosition();
                return false;
            }
            return true;
        }

        @Override
        public boolean visit(EnumConstantDeclaration declaration) {
            if (matches(declaration.resolveConstructorBinding())) {
                this.offset = declaration.getName().getStartPosition();
                return false;
            }
            return true;
        }

        @Override
        public boolean visit(StringLiteral literal) {
            if (this.query instanceof ReferenceQuery.StringLiteralReference target
                    && literal.getLiteralValue().equals(target.value())) {
                this.offset = literal.getStartPosition();
                return false;
            }
            return true;
        }

        @Override
        public boolean visit(TextBlock literal) {
            if (this.query instanceof ReferenceQuery.StringLiteralReference target
                    && literal.getLiteralValue().equals(target.value())) {
                this.offset = literal.getStartPosition();
                return false;
            }
            return true;
        }

        private boolean matches(IBinding binding) {
            if (binding == null) {
                return false;
            }
            CodeSymbol symbol = JavaSymbolResolver.trySymbolForBinding(binding);
            return symbol != null && symbol.referenceQuery().equals(this.query);
        }
    }

    private static boolean isDeclarationName(SimpleName name) {
        ASTNode parent = name.getParent();
        return switch (parent) {
            case AbstractTypeDeclaration declaration -> declaration.getName() == name;
            case MethodDeclaration declaration -> declaration.getName() == name;
            case VariableDeclaration declaration -> declaration.getName() == name;
            case EnumConstantDeclaration declaration -> declaration.getName() == name;
            case AnnotationTypeMemberDeclaration declaration -> declaration.getName() == name;
            default -> false;
        };
    }
}
