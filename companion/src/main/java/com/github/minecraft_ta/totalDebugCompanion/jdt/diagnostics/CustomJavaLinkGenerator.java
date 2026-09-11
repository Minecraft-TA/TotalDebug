package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.RuntimeMember;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.SourceRange;
import org.eclipse.jdt.internal.core.*;
import org.fife.ui.rsyntaxtextarea.LinkGenerator;
import org.fife.ui.rsyntaxtextarea.LinkGeneratorResult;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Token;

import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntFunction;

public class CustomJavaLinkGenerator implements LinkGenerator {

    @FunctionalInterface
    interface ElementResolver {
        IJavaElement resolve(int offset) throws JavaModelException;
    }

    private final ElementResolver elementResolver;
    private final IntFunction<String> ownerClassResolver;
    private final BiConsumer<String, String> packageNavigator;
    private final Consumer<NavigationTarget> navigator;
    private final Path sourcePath;

    public CustomJavaLinkGenerator(String identifier, Consumer<NavigationTarget> navigator) {
        this(
                offset -> JavaSymbolResolver.selectElement(identifier, offset),
                offset -> JavaSymbolResolver.navigationOwnerClass(identifier, offset),
                (name, owner) -> navigator.accept(new NavigationTarget.RuntimePackage(name, owner)),
                Path.of(identifier),
                navigator
        );
    }

    CustomJavaLinkGenerator(
            ElementResolver elementResolver,
            IntFunction<String> ownerClassResolver,
            BiConsumer<String, String> packageNavigator,
            Path sourcePath
    ) {
        this(elementResolver, ownerClassResolver, packageNavigator, sourcePath, ignored -> {
        });
    }

    CustomJavaLinkGenerator(
            ElementResolver elementResolver,
            IntFunction<String> ownerClassResolver,
            BiConsumer<String, String> packageNavigator,
            Path sourcePath,
            Consumer<NavigationTarget> navigator
    ) {
        this.elementResolver = Objects.requireNonNull(elementResolver, "elementResolver");
        this.ownerClassResolver = Objects.requireNonNull(ownerClassResolver, "ownerClassResolver");
        this.packageNavigator = Objects.requireNonNull(packageNavigator, "packageNavigator");
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
    }

    @Override
    public LinkGeneratorResult isLinkAtOffset(RSyntaxTextArea textArea, int offs) {
        try {
            if (offs < 0
                    || offs >= textArea.getDocument().getLength()
                    || !Character.isJavaIdentifierPart(textArea.getText(offs, 1).charAt(0))) {
                return null;
            }
            Token token = textArea.modelToToken(offs);
            if (token == null || !token.containsPosition(offs)) {
                return null;
            }
            IJavaElement element = this.elementResolver.resolve(offs);
            if (element == null) {
                return null;
            }
            return new LinkResult(element, offs, token.getOffset());
        } catch (JavaModelException | BadLocationException e) {
            e.printStackTrace();
            return null;
        }
    }

    private class LinkResult implements LinkGeneratorResult {

        private final IJavaElement el;
        private final int navigationOffset;
        private final int sourceOffset;

        public LinkResult(IJavaElement el, int navigationOffset, int sourceOffset) {
            this.el = el;
            this.navigationOffset = navigationOffset;
            this.sourceOffset = sourceOffset;
        }

        @Override
        public HyperlinkEvent execute() {
            try {
                if (el instanceof LocalVariable || el instanceof SourceMethod || el instanceof SourceField || el instanceof SourceType) {
                    var sourceRange = switch (el) {
                        case LocalVariable lv ->
                                SourceRange.isAvailable(lv.getNameRange()) ? lv.getNameRange() : lv.getSourceRange();
                        case SourceRefElement sr ->
                                SourceRange.isAvailable(sr.getNameRange()) ? sr.getNameRange() : sr.getSourceRange();
                        default -> throw new IllegalStateException();
                    };

                    if (!SourceRange.isAvailable(sourceRange)) {
                        System.err.println("SourceRange is not available for " + el);
                        return null;
                    }

                    int editorOffset = ASTCache.toEditorOffset(sourcePath.toString(), sourceRange.getOffset());
                    if (editorOffset < 0) {
                        return null;
                    }
                    navigator.accept(new NavigationTarget.LocalFile(
                            sourcePath,
                            editorOffset
                    ));
                    return null;
                }

                if (el instanceof IPackageFragment packageFragment) {
                    packageNavigator.accept(
                            packageFragment.getElementName(),
                            ownerClassResolver.apply(this.navigationOffset)
                    );
                    return null;
                }

                switch (el) {
                    case ResolvedBinaryMethod ignored -> navigateResolvedSymbol();
                    case ResolvedBinaryField ignored -> navigateResolvedSymbol();
                    case ResolvedBinaryType type -> navigator.accept(
                            new NavigationTarget.RuntimeClass(type.getFullyQualifiedName())
                    );
                    default -> {
                        return null;
                    }
                }
            } catch (JavaModelException e) {
                e.printStackTrace();
            }

            return null;
        }

        @Override
        public int getSourceOffset() {
            return this.sourceOffset;
        }

        private void navigateResolvedSymbol() throws JavaModelException {
            JavaSymbolResolver.Resolution resolution = JavaSymbolResolver.resolve(
                    sourcePath.toString(),
                    this.navigationOffset
            );
            if (resolution.symbol() == null) {
                throw new IllegalStateException(resolution.unavailableReason());
            }
            navigator.accept(switch (resolution.symbol()) {
                case com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol.ClassSymbol type ->
                        new NavigationTarget.RuntimeClass(type.className());
                case com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol.FieldSymbol field ->
                        new NavigationTarget.RuntimeDeclaration(RuntimeMember.from(field));
                case com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol.MethodSymbol method ->
                        new NavigationTarget.RuntimeDeclaration(RuntimeMember.from(method));
            });
        }
    }
}
