package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclaration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.model.UsagesView;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.HierarchyPreviewPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.ImplementationChooserPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import org.eclipse.jdt.core.JavaModelException;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.KeyStroke;
import javax.swing.border.CompoundBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class CodeViewPanel extends AbstractCodeViewPanel {
    private static final String FIND_IMPLEMENTATIONS_KEY = "findImplementations";
    private static final String FIND_BASE_METHODS_KEY = "findBaseMethods";
    private static final String FIND_USAGES_KEY = "findUsages";

    private final ImplementationChooserPopup implementationChooser;
    private final HierarchyPreviewPopup hierarchyPreview;
    private final CodeVisionLayerUI codeVisionLayerUI;
    private final JLayer<RTextScrollPane> codeVisionLayer;
    private final HierarchyGutterMarkers gutterMarkers;
    private final CodeVisionController codeVisionController;
    private boolean codeVisionDisposed;

    public CodeViewPanel(CodeView codeView) {
        super(codeView.getPath().toString(), codeView.getTitle());
        this.editorPane.setEditable(false);
        this.editorPane.setBorder(new CompoundBorder(
                this.editorPane.getBorder(),
                BorderFactory.createEmptyBorder(0, 0, 0, 180)
        ));
        enableSearch();

        var insightService = CompanionApp.getCodeInsightService();
        this.implementationChooser = new ImplementationChooserPopup(MainWindow.INSTANCE, insightService);
        this.implementationChooser.setListFont(this.editorPane.getFont());
        this.hierarchyPreview = new HierarchyPreviewPopup(MainWindow.INSTANCE, insightService);
        this.hierarchyPreview.setContentFont(this.editorPane.getFont());

        this.codeVisionLayerUI = new CodeVisionLayerUI(this.editorPane, new CodeVisionLayerUI.Handler() {
            @Override
            public void showUsages(CodeSymbol symbol) {
                CodeViewPanel.this.showUsages(symbol);
            }

            @Override
            public void showImplementations(CodeSymbol symbol, int anchorOffset) {
                CodeViewPanel.this.showImplementations(symbol, anchorOffset);
            }
        });
        this.codeVisionLayer = new JLayer<>(this.editorScrollPane, this.codeVisionLayerUI);
        remove(this.editorScrollPane);
        add(this.codeVisionLayer, BorderLayout.CENTER);

        this.gutterMarkers = new HierarchyGutterMarkers(
                this.editorScrollPane.getGutter(),
                new HierarchyGutterMarkers.Handler() {
                    @Override
                    public void showImplementations(SourceDeclaration declaration) {
                        CodeViewPanel.this.showImplementations(
                                declaration.symbol(),
                                declaration.markerOffset()
                        );
                    }

                    @Override
                    public void showBaseMethods(SourceDeclaration declaration) {
                        CodeViewPanel.this.showBaseMethods(
                                declaration.symbol(),
                                declaration.markerOffset()
                        );
                    }

                    @Override
                    public void previewImplementations(SourceDeclaration declaration, Component invoker, Point point) {
                        hierarchyPreview.showImplementations(invoker, point, declaration.symbol());
                    }

                    @Override
                    public void previewBaseMethods(SourceDeclaration declaration, Component invoker, Point point) {
                        if (declaration.symbol() instanceof CodeSymbol.MethodSymbol method) {
                            hierarchyPreview.showBaseMethods(invoker, point, method);
                        }
                    }

                    @Override
                    public void hidePreview() {
                        hierarchyPreview.hidePreview();
                    }
                }
        );
        this.codeVisionController = new CodeVisionController(
                this.identifier,
                insightService,
                this.codeVisionLayerUI,
                this.codeVisionLayer,
                this.gutterMarkers
        );

        configureActions();
        configureContextMenuCaret();
        this.editorPane.getCaret().addChangeListener(event -> {
            this.implementationChooser.setVisible(false);
            this.hierarchyPreview.hidePreview();
        });
    }

    public void setCode(String code) {
        CodeUtils.initSyntaxScheme(this.editorPane);
        this.editorPane.setText(code);
    }

    @Override
    protected void updateFonts() {
        super.updateFonts();
        if (this.implementationChooser != null) {
            this.implementationChooser.setListFont(this.editorPane.getFont());
        }
        if (this.hierarchyPreview != null) {
            this.hierarchyPreview.setContentFont(this.editorPane.getFont());
        }
    }

    @Override
    public void dispose() {
        if (!this.codeVisionDisposed) {
            this.codeVisionDisposed = true;
            this.codeVisionController.close();
            this.implementationChooser.dispose();
            this.hierarchyPreview.dispose();
        }
        super.dispose();
    }

    private void configureActions() {
        AbstractAction implementations = new AbstractAction("Go to Implementations", Icons.JAVA_INTERFACE) {
            @Override
            public void actionPerformed(ActionEvent event) {
                resolveSelectedSymbol("implementation navigation", symbol ->
                        showImplementations(symbol, editorPane.getCaretPosition())
                );
            }
        };
        AbstractAction bases = new AbstractAction("Go to Base Method", Icons.JAVA_METHOD) {
            @Override
            public void actionPerformed(ActionEvent event) {
                resolveSelectedSymbol("base-method navigation", symbol ->
                        showBaseMethods(symbol, editorPane.getCaretPosition())
                );
            }
        };
        AbstractAction usages = new AbstractAction("Find Usages", Icons.SEARCH_ICON) {
            @Override
            public void actionPerformed(ActionEvent event) {
                resolveSelectedSymbol("Find Usages", CodeViewPanel.this::showUsages);
            }
        };

        this.editorPane.getActionMap().put(FIND_IMPLEMENTATIONS_KEY, implementations);
        this.editorPane.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK),
                FIND_IMPLEMENTATIONS_KEY
        );
        this.editorPane.getActionMap().put(FIND_BASE_METHODS_KEY, bases);
        this.editorPane.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK),
                FIND_BASE_METHODS_KEY
        );
        this.editorPane.getActionMap().put(FIND_USAGES_KEY, usages);
        this.editorPane.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F7, InputEvent.ALT_DOWN_MASK),
                FIND_USAGES_KEY
        );

        var menu = this.editorPane.getPopupMenu();
        menu.addSeparator();
        var usageItem = menu.add(usages);
        usageItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F7, InputEvent.ALT_DOWN_MASK));
        var implementationItem = menu.add(implementations);
        implementationItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK));
        var baseItem = menu.add(bases);
        baseItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK));
    }

    private void configureContextMenuCaret() {
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

    private void showUsages(CodeSymbol symbol) {
        MainWindow.INSTANCE.getEditorTabs().focusOrCreateIfAbsent(
                UsagesView.class,
                view -> view.symbol().equals(symbol),
                () -> new UsagesView(symbol)
        ).thenAccept(UsagesView::restartSearch);
    }

    private void showImplementations(CodeSymbol symbol, int anchorOffset) {
        if (symbol instanceof CodeSymbol.FieldSymbol) {
            this.bottomInformationBar.setDefaultInfoText("Fields do not have implementations");
            return;
        }
        this.implementationChooser.showImplementations(this.editorPane, symbol, anchorOffset);
    }

    private void showBaseMethods(CodeSymbol symbol, int anchorOffset) {
        if (!(symbol instanceof CodeSymbol.MethodSymbol method)) {
            this.bottomInformationBar.setDefaultInfoText("Only methods have base declarations");
            return;
        }
        this.implementationChooser.showBaseMethods(this.editorPane, method, anchorOffset);
    }

    private void resolveSelectedSymbol(String action, java.util.function.Consumer<CodeSymbol> consumer) {
        try {
            var resolution = JavaSymbolResolver.resolve(this.identifier, this.editorPane.getCaretPosition());
            if (!resolution.isResolved()) {
                this.bottomInformationBar.setDefaultInfoText(resolution.unavailableReason());
                return;
            }
            consumer.accept(resolution.symbol());
        } catch (JavaModelException exception) {
            exception.printStackTrace(System.err);
            this.bottomInformationBar.setFailureInfoText("Unable to resolve the selected symbol for " + action);
        }
    }
}
