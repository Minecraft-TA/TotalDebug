package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.SourceRange;
import org.eclipse.jdt.internal.core.*;
import org.fife.ui.rsyntaxtextarea.LinkGenerator;
import org.fife.ui.rsyntaxtextarea.LinkGeneratorResult;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;

public class CustomJavaLinkGenerator implements LinkGenerator {

    @FunctionalInterface
    interface ElementResolver {
        IJavaElement resolve(int offset) throws JavaModelException;
    }

    private final ElementResolver elementResolver;
    private final IntFunction<String> ownerClassResolver;
    private final BiConsumer<String, String> packageNavigator;

    public CustomJavaLinkGenerator(String identifier) {
        this(
                offset -> JavaSymbolResolver.selectElement(identifier, offset),
                offset -> JavaSymbolResolver.navigationOwnerClass(identifier, offset),
                (packageName, ownerClass) -> MainWindow.INSTANCE.revealPackage(packageName, ownerClass)
        );
    }

    CustomJavaLinkGenerator(
            ElementResolver elementResolver,
            IntFunction<String> ownerClassResolver,
            BiConsumer<String, String> packageNavigator
    ) {
        this.elementResolver = Objects.requireNonNull(elementResolver, "elementResolver");
        this.ownerClassResolver = Objects.requireNonNull(ownerClassResolver, "ownerClassResolver");
        this.packageNavigator = Objects.requireNonNull(packageNavigator, "packageNavigator");
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
            return new LinkResult(textArea, element, offs, token.getOffset());
        } catch (JavaModelException | BadLocationException e) {
            e.printStackTrace();
            return null;
        }
    }

    private class LinkResult implements LinkGeneratorResult {

        private final RSyntaxTextArea textArea;
        private final IJavaElement el;
        private final int navigationOffset;
        private final int sourceOffset;

        public LinkResult(RSyntaxTextArea textArea, IJavaElement el, int navigationOffset, int sourceOffset) {
            this.textArea = textArea;
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

                    UIUtils.centerViewportOnRange(((RTextScrollPane) textArea.getParent().getParent()), sourceRange.getOffset(), sourceRange.getOffset());
                    return null;
                }

                if (el instanceof IPackageFragment packageFragment) {
                    packageNavigator.accept(
                            packageFragment.getElementName(),
                            ownerClassResolver.apply(this.navigationOffset)
                    );
                    return null;
                }

                String className;
                int targetMemberType = -1;
                String targetMemberIdentifier = "";
                switch (el) {
                    case ResolvedBinaryMethod method:
                        className = method.getDeclaringType().getFullyQualifiedName();
                        targetMemberType = method.getElementType();
                        targetMemberIdentifier = method.getKey();
                        break;
                    case ResolvedBinaryField field:
                        className = field.getDeclaringType().getFullyQualifiedName();
                        targetMemberType = field.getElementType();
                        targetMemberIdentifier = field.getElementName();
                        break;
                    case ResolvedBinaryType type:
                        className = type.getFullyQualifiedName();
                        break;
                    default:
                        return null;
                }

                CompanionApp.openClass(className, targetMemberType, targetMemberIdentifier);
            } catch (JavaModelException e) {
                e.printStackTrace();
            }

            return null;
        }

        @Override
        public int getSourceOffset() {
            return this.sourceOffset;
        }
    }
}
