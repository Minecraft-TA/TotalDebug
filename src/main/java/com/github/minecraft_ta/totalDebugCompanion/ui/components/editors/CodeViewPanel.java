package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyDirection;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyRelation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.SymbolInsight;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclaration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.model.UsagesView;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.HierarchyPreviewPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.ImplementationChooserPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import org.eclipse.jdt.core.JavaModelException;

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
import java.util.List;
import java.util.Map;

public class CodeViewPanel extends AbstractCodeViewPanel {
    private static final String FIND_IMPLEMENTATIONS_KEY = "findImplementations";
    private static final String FIND_BASE_METHODS_KEY = "findBaseMethods";
    private static final String FIND_USAGES_KEY = "findUsages";

    private final ImplementationChooserPopup implementationChooser;
    private final HierarchyPreviewPopup hierarchyPreview;
    private final CodeInsightService insightService;
    private final CodeVisionLayerUI codeVisionLayerUI;
    private final JLayer<JComponent> codeVisionLayer;
    private final HierarchyGutterMarkers gutterMarkers;
    private final CodeVisionController codeVisionController;
    private boolean codeVisionDisposed;
    private CodeInsightService.SearchHandle actionSearch;

    public CodeViewPanel(CodeView codeView) {
        super(codeView.getPath().toString(), codeView.getTitle());
        this.editorPane.setEditable(false);
        this.editorPane.setBorder(new CompoundBorder(
                this.editorPane.getBorder(),
                BorderFactory.createEmptyBorder(0, 0, 0, 180)
        ));
        enableSearch();

        this.insightService = CompanionApp.getCodeInsightService();
        this.implementationChooser = new ImplementationChooserPopup(MainWindow.INSTANCE, this.insightService);
        this.implementationChooser.setListFont(this.editorPane.getFont());
        this.hierarchyPreview = new HierarchyPreviewPopup(MainWindow.INSTANCE, this.insightService);
        this.hierarchyPreview.setContentFont(this.editorPane.getFont());

        this.codeVisionLayerUI = new CodeVisionLayerUI(this.editorPane, new CodeVisionLayerUI.Handler() {
            @Override
            public void showUsages(CodeSymbol symbol) {
                CodeViewPanel.this.showUsages(symbol);
            }

            @Override
            public void showHierarchy(
                    CodeSymbol symbol,
                    HierarchyRelation relation,
                    int count,
                    int anchorOffset
            ) {
                CodeViewPanel.this.navigateHierarchy(symbol, relation, count, anchorOffset);
            }
        });
        this.codeVisionLayer = new JLayer<>(this.editorLayer, this.codeVisionLayerUI);
        remove(this.editorLayer);
        add(this.codeVisionLayer, BorderLayout.CENTER);

        this.gutterMarkers = new HierarchyGutterMarkers(
                this.editorScrollPane.getGutter(),
                new HierarchyGutterMarkers.Handler() {
                    @Override
                    public void navigate(
                            SourceDeclaration declaration,
                            HierarchyRelation relation,
                            int count
                    ) {
                        CodeViewPanel.this.navigateHierarchy(
                                declaration.symbol(),
                                relation,
                                count,
                                declaration.markerOffset()
                        );
                    }

                    @Override
                    public void preview(
                            SourceDeclaration declaration,
                            HierarchyRelation relation,
                            int count,
                            boolean mixedBaseRelations,
                            Component invoker,
                            Point point
                    ) {
                        hierarchyPreview.showHierarchy(
                                invoker,
                                point,
                                declaration.symbol(),
                                relation,
                                count,
                                mixedBaseRelations
                        );
                    }

                    @Override
                    public void hidePreview() {
                        hierarchyPreview.hidePreview();
                    }
                }
        );
        this.codeVisionController = new CodeVisionController(
                this.identifier,
                this.insightService,
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
            if (this.actionSearch != null) {
                this.actionSearch.cancel();
                this.actionSearch = null;
            }
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
        resolveHierarchyAction(symbol, HierarchyDirection.IMPLEMENTATIONS, anchorOffset);
    }

    private void showBaseMethods(CodeSymbol symbol, int anchorOffset) {
        if (!(symbol instanceof CodeSymbol.MethodSymbol method)) {
            this.bottomInformationBar.setDefaultInfoText("Only methods have base declarations");
            return;
        }
        resolveHierarchyAction(method, HierarchyDirection.BASE_METHODS, anchorOffset);
    }

    private void resolveHierarchyAction(
            CodeSymbol symbol,
            HierarchyDirection direction,
            int anchorOffset
    ) {
        if (this.actionSearch != null) {
            this.actionSearch.cancel();
        }
        this.actionSearch = this.insightService.summarize(List.of(symbol), new CodeInsightService.Listener<>() {
            @Override
            public void onCompleted(Map<CodeSymbol, SymbolInsight> result) {
                actionSearch = null;
                var insight = result.get(symbol);
                int count = insight == null ? 0 : insight.count(direction);
                if (count == 0) {
                    bottomInformationBar.setDefaultInfoText(direction == HierarchyDirection.BASE_METHODS
                            ? "No base declarations found"
                            : "No implementations or overrides found");
                    return;
                }
                HierarchyRelation relation = direction == HierarchyDirection.IMPLEMENTATIONS
                        ? insight.descendantFacet().orElseThrow().relation()
                        : insight.count(HierarchyRelation.OVERRIDES) > 0
                                ? HierarchyRelation.OVERRIDES
                                : HierarchyRelation.IMPLEMENTS;
                navigateHierarchy(symbol, relation, count, anchorOffset);
            }

            @Override
            public void onFailed(Throwable failure) {
                actionSearch = null;
                failure.printStackTrace(System.err);
                bottomInformationBar.setFailureInfoText("Unable to inspect the selected hierarchy");
            }
        });
    }

    private void navigateHierarchy(
            CodeSymbol symbol,
            HierarchyRelation relation,
            int count,
            int anchorOffset
    ) {
        this.implementationChooser.navigate(this.editorPane, symbol, relation, count, anchorOffset);
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
