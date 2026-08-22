package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.search.SearchManager;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.SearchHeaderBar;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.BasePopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.FindImplementationsPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.tth05.jindex.IndexedMethod;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.core.SourceMethod;
import org.eclipse.jdt.internal.core.SourceType;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.*;
import javax.swing.text.TextAction;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.util.Arrays;

public class CodeViewPanel extends AbstractCodeViewPanel {

    private static final FindImplementationsPopup FIND_IMPLEMENTATIONS_POPUP = new FindImplementationsPopup(MainWindow.INSTANCE);

    private final SearchManager searchManager = new SearchManager(editorPane);

    public CodeViewPanel(CodeView codeView) {
        super(codeView.getPath().toString(), codeView.getTitle());
        this.editorPane.setEditable(false);

        //Ctrl+F keybind for search
        this.editorPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ctrl pressed F"), "openSearchPopup");
        this.editorPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "closeSearchPopup");
        this.editorPane.getActionMap().put("closeSearchPopup", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                removeHeaderComponent();
                searchManager.hideHighlights();
            }
        });
        this.editorPane.getActionMap().put("openSearchPopup", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setHeaderComponent(new SearchHeaderBar(searchManager));
            }
        });

        this.editorPane.getActionMap().put(FindImplementationsAction.KEY, new FindImplementationsAction());
        this.editorPane.getInputMap().put(KeyStroke.getKeyStroke("ctrl T"), FindImplementationsAction.KEY);
        this.editorPane.getCaret().addChangeListener(e -> FIND_IMPLEMENTATIONS_POPUP.setVisible(false));

        //Stop search thread if the tab is closed
        addHierarchyListener(e -> {
            if (e.getChangeFlags() == HierarchyEvent.PARENT_CHANGED && getParent() == null) {
                this.searchManager.stopThread();
            }
        });

        //Scroll to focused search position
        this.searchManager.addFocusedIndexChangedListener(i -> {
            if (this.searchManager.getMatchCount() == 0)
                return;

            SwingUtilities.invokeLater(() -> UIUtils.centerViewportOnRange(this.editorScrollPane, this.searchManager.getFocusedRangeStart(), this.searchManager.getFocusedRangeEnd()));
        });
    }

    public void setCode(String code) {
        CodeUtils.initSyntaxScheme(this.editorPane);
        this.editorPane.setText(code);
    }

    @Override
    protected void updateFonts() {
        super.updateFonts();
        SwingUtilities.invokeLater(() -> {
            var newFont = JETBRAINS_MONO_FONT.deriveFont(GlobalConfig.getInstance().<Float>getValue("fontSize"));
            FIND_IMPLEMENTATIONS_POPUP.setFont(newFont);
        });
    }

    private class FindImplementationsAction extends TextAction {

        private static final String KEY = "findImplementations";

        public FindImplementationsAction() {
            super(KEY);
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            var textArea = (RSyntaxTextArea) getTextComponent(event);

            try {
                var fromCache = ASTCache.getFromCache(identifier);
                if (fromCache == null)
                    return;

                var offset = textArea.getCaretPosition();
                var elements = fromCache.getTypeRoot().codeSelect(offset, 0);
                if (elements == null || elements.length == 0)
                    return;
                if (elements.length > 1) {
                    System.err.println("Multiple elements found at offset " + offset + ": " + Arrays.toString(elements));
                    return;
                }

                var el = elements[0];
                if (el.getClass() == SourceMethod.class || el.getClass() == SourceType.class) {
                    switch (el) {
                        case SourceMethod sr -> {
                            var className = CodeUtils.splitTypeName(sr.getDeclaringType().getFullyQualifiedName());
                            var declaringClass = CompanionClassIndex.get().findClass(className[0], className[1]);
                            if (declaringClass == null) {
                                System.err.println("Declaring class not found in index: " + className[0] + "." + className[1]);
                                return;
                            }

                            var targetMethodName = sr.getElementName();
                            if (targetMethodName.equals(declaringClass.getName()))
                                targetMethodName = "<init>";

                            var targetDescriptor = CodeUtils.minimalizeMethodIdentifier(sr.getSignature(), false);
                            for (IndexedMethod method : declaringClass.getMethods()) {
                                var genericSig = method.getGenericSignatureString();
                                if (method.getName().equals(targetMethodName) &&
                                    CodeUtils.minimalizeMethodIdentifier(genericSig != null ? genericSig : method.getDescriptorString(), true).equals(targetDescriptor)) {
                                    FIND_IMPLEMENTATIONS_POPUP.setItems(method);
                                    FIND_IMPLEMENTATIONS_POPUP.show(editorPane, BasePopup.Alignment.BOTTOM_CENTER);
                                    return;
                                }
                            }

                            System.err.println("Method not found in declaring class: " + declaringClass + " " + sr.getSignature());
                        }
                        case SourceType st -> {
                            var className = CodeUtils.splitTypeName(st.getFullyQualifiedName());
                            var indexedClass = CompanionClassIndex.get().findClass(className[0], className[1]);
                            if (indexedClass == null) {
                                System.err.println("Class not found in index: " + st.getFullyQualifiedName());
                                return;
                            }

                            FIND_IMPLEMENTATIONS_POPUP.setItems(indexedClass);
                            FIND_IMPLEMENTATIONS_POPUP.show(editorPane, BasePopup.Alignment.BOTTOM_CENTER);
                        }
                        default -> {}
                    }
                }
            } catch (JavaModelException e) {
                e.printStackTrace();
            }
        }
    }
}
