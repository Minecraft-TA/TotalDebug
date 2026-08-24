package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.model.UsagesView;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.BasePopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.FindImplementationsPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import com.github.tth05.jindex.IndexedMethod;
import org.eclipse.jdt.core.JavaModelException;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.*;
import javax.swing.text.TextAction;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class CodeViewPanel extends AbstractCodeViewPanel {

    private static final FindImplementationsPopup FIND_IMPLEMENTATIONS_POPUP = new FindImplementationsPopup(MainWindow.INSTANCE);

    public CodeViewPanel(CodeView codeView) {
        super(codeView.getPath().toString(), codeView.getTitle());
        this.editorPane.setEditable(false);
        enableSearch();

        this.editorPane.getActionMap().put(FindImplementationsAction.KEY, new FindImplementationsAction());
        this.editorPane.getInputMap().put(KeyStroke.getKeyStroke("ctrl T"), FindImplementationsAction.KEY);
        this.editorPane.getCaret().addChangeListener(e -> FIND_IMPLEMENTATIONS_POPUP.setVisible(false));

        var findUsagesAction = new FindUsagesAction();
        this.editorPane.getActionMap().put(FindUsagesAction.KEY, findUsagesAction);
        this.editorPane.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F7, InputEvent.ALT_DOWN_MASK),
                FindUsagesAction.KEY
        );
        var popupMenu = this.editorPane.getPopupMenu();
        popupMenu.addSeparator();
        var findUsagesItem = popupMenu.add(findUsagesAction);
        findUsagesItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F7, InputEvent.ALT_DOWN_MASK));
        this.editorPane.addMouseListener(new MouseAdapter() {
            private void moveCaretToPopup(MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }
                int offset = editorPane.viewToModel2D(event.getPoint());
                if (offset >= 0) {
                    editorPane.setCaretPosition(offset);
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                moveCaretToPopup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                moveCaretToPopup(event);
            }
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
            var newFont = JETBRAINS_MONO_FONT.deriveFont(GlobalConfig.getInstance().editorFontSize());
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
                var offset = textArea.getCaretPosition();
                var resolution = JavaSymbolResolver.resolve(identifier, offset);
                if (!resolution.isResolved()) {
                    bottomInformationBar.setDefaultInfoText(resolution.unavailableReason());
                    return;
                }

                switch (resolution.symbol()) {
                    case CodeSymbol.ClassSymbol type -> {
                        var indexedClass = findIndexedClass(type.className());
                        if (indexedClass == null) {
                            bottomInformationBar.setFailureInfoText("Class is not present in the runtime index");
                            return;
                        }
                        FIND_IMPLEMENTATIONS_POPUP.setItems(indexedClass);
                        FIND_IMPLEMENTATIONS_POPUP.show(editorPane, BasePopup.Alignment.BOTTOM_CENTER);
                    }
                    case CodeSymbol.MethodSymbol target -> showMethodImplementations(target);
                    case CodeSymbol.FieldSymbol ignored ->
                            bottomInformationBar.setDefaultInfoText("Fields do not have implementations");
                }
            } catch (JavaModelException e) {
                e.printStackTrace();
                bottomInformationBar.setFailureInfoText("Unable to resolve the selected Java symbol");
            }
        }

        private void showMethodImplementations(CodeSymbol.MethodSymbol target) {
            var declaringClass = findIndexedClass(target.ownerClassName());
            if (declaringClass == null) {
                bottomInformationBar.setFailureInfoText("Declaring class is not present in the runtime index");
                return;
            }
            for (IndexedMethod method : declaringClass.getMethods()) {
                if (method.getName().equals(target.name())
                        && method.getDescriptorString().equals(target.descriptor())) {
                    FIND_IMPLEMENTATIONS_POPUP.setItems(method);
                    FIND_IMPLEMENTATIONS_POPUP.show(editorPane, BasePopup.Alignment.BOTTOM_CENTER);
                    return;
                }
            }
            bottomInformationBar.setFailureInfoText("Method is not present in the runtime index");
        }
    }

    private class FindUsagesAction extends AbstractAction {
        private static final String KEY = "findUsages";

        private FindUsagesAction() {
            super("Find Usages");
            putValue(SMALL_ICON, Icons.SEARCH_ICON);
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            try {
                var resolution = JavaSymbolResolver.resolve(identifier, editorPane.getCaretPosition());
                if (!resolution.isResolved()) {
                    bottomInformationBar.setDefaultInfoText(resolution.unavailableReason());
                    return;
                }

                CodeSymbol symbol = resolution.symbol();
                MainWindow.INSTANCE.getEditorTabs().focusOrCreateIfAbsent(
                        UsagesView.class,
                        view -> view.symbol().equals(symbol),
                        () -> new UsagesView(symbol)
                ).thenAccept(UsagesView::restartSearch);
            } catch (JavaModelException exception) {
                exception.printStackTrace();
                bottomInformationBar.setFailureInfoText("Unable to resolve the selected Java symbol");
            }
        }
    }

    private static com.github.tth05.jindex.IndexedClass findIndexedClass(String binaryName) {
        var className = CodeUtils.splitTypeName(binaryName);
        return CompanionClassIndex.get().findClass(className[0], className[1]);
    }
}
