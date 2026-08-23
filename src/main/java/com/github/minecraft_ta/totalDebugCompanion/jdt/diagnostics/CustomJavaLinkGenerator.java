package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import com.github.minecraft_ta.totalDebugCompanion.messages.codeView.DecompileOrOpenMessage;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.BottomInformationBar;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.SourceRange;
import org.eclipse.jdt.internal.core.*;
import org.fife.ui.rsyntaxtextarea.LinkGenerator;
import org.fife.ui.rsyntaxtextarea.LinkGeneratorResult;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.event.HyperlinkEvent;

public class CustomJavaLinkGenerator implements LinkGenerator {

    private final String identifier;
    private final BottomInformationBar informationBar;

    public CustomJavaLinkGenerator(String identifier, BottomInformationBar informationBar) {
        this.identifier = identifier;
        this.informationBar = informationBar;
    }

    @Override
    public LinkGeneratorResult isLinkAtOffset(RSyntaxTextArea textArea, int offs) {
        try {
            IJavaElement element = JavaSymbolResolver.selectElement(this.identifier, offs);
            if (element == null) {
                return null;
            }
            return new LinkResult(textArea, element, offs);
        } catch (JavaModelException e) {
            e.printStackTrace();
            return null;
        }
    }

    private class LinkResult implements LinkGeneratorResult {

        private final RSyntaxTextArea textArea;
        private final IJavaElement el;
        private final int offs;

        public LinkResult(RSyntaxTextArea textArea, IJavaElement el, int offs) {
            this.textArea = textArea;
            this.el = el;
            this.offs = offs;
        }

        @Override
        public HyperlinkEvent execute() {
            if (!CompanionApp.SERVER.isClientConnected()) {
                informationBar.setFailureInfoText("Not connected to game client!");
                return null;
            }

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

                CompanionApp.send(new DecompileOrOpenMessage(className, targetMemberType, targetMemberIdentifier));
            } catch (JavaModelException e) {
                e.printStackTrace();
            }

            return null;
        }

        @Override
        public int getSourceOffset() {
            return offs;
        }
    }
}
