package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyDirection;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyRelation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.SymbolInsight;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclaration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclarationAnalyzer;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.HierarchyPreviewPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.ImplementationChooserPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.util.CodeUtils;
import org.eclipse.jdt.core.JavaModelException;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.text.BadLocationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CodeViewPanel extends AbstractCodeViewPanel {
    private static final String FIND_IMPLEMENTATIONS_KEY = "findImplementations";
    private static final String FIND_BASE_METHODS_KEY = "findBaseMethods";
    private static final String FIND_USAGES_KEY = "findUsages";
    private static final String TOGGLE_BREAKPOINT_KEY = "toggleBreakpoint";
    private static final KeyStroke TOGGLE_BREAKPOINT_SHORTCUT =
            KeyStroke.getKeyStroke(KeyEvent.VK_F8, InputEvent.CTRL_DOWN_MASK);

    private final ImplementationChooserPopup implementationChooser;
    private final HierarchyPreviewPopup hierarchyPreview;
    private final CodeInsightService insightService;
    private final CodeVisionLayerUI codeVisionLayerUI;
    private final JLayer<JComponent> codeVisionLayer;
    private final HierarchyGutterMarkers gutterMarkers;
    private final CodeVisionController codeVisionController;
    private final DebugEngine.Source debugSource;
    private final BreakpointGutterMarkers breakpointMarkers;
    private final BreakpointEditorPopup breakpointEditor = new BreakpointEditorPopup();
    private final DebuggerSessionController.Listener debuggerListener;
    private boolean codeVisionDisposed;
    private CodeInsightService.SearchHandle actionSearch;
    private final DebuggerLineHighlights debuggerLineHighlights;

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

        EditorGutter editorGutter = new EditorGutter(this.editorScrollPane.getGutter());
        this.debuggerLineHighlights = new DebuggerLineHighlights(
                this.editorPane,
                ThemeManager.palette()
        );
        this.gutterMarkers = new HierarchyGutterMarkers(
                editorGutter,
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

        this.debugSource = codeView.getDebugSource().orElse(null);
        if (this.debugSource == null) {
            this.breakpointMarkers = null;
            this.debuggerListener = null;
        } else {
            DebuggerSessionController debugger = CompanionApp.getDebuggerController();
            debugger.registerSource(this.debugSource);
            this.breakpointMarkers = new BreakpointGutterMarkers(
                    editorGutter,
                    this.editorPane,
                    this.editorLayer,
                    new BreakpointGutterMarkers.Handler() {
                        @Override
                        public void toggle(int displayedLine) {
                            toggleBreakpointAtLine(displayedLine);
                        }

                        @Override
                        public void configure(int displayedLine, Component invoker, Point location) {
                            showBreakpointEditor(displayedLine, invoker, location);
                        }
                    }
            );
            this.editorChromeLayerUI.setBreakpointMarkers(this.breakpointMarkers);
            updateBreakpointMarkers(debugger);
            this.debuggerListener = new DebuggerSessionController.Listener() {
                @Override
                public void statusChanged(DebuggerSessionController.Status status) {
                    SwingUtilities.invokeLater(() -> {
                        updateBreakpointMarkers(debugger);
                        if (status.phase() != DebuggerSessionController.Phase.PAUSED) {
                            clearExecutionLine();
                        }
                    });
                }

                @Override
                public void breakpointsChanged(
                        java.net.URI sourceUri,
                        List<DebuggerSessionController.Breakpoint> breakpoints
                ) {
                    if (!debugSource.uri().equals(sourceUri)) {
                        return;
                    }
                    SwingUtilities.invokeLater(() -> updateBreakpointMarkers(debugger));
                }
            };
            debugger.addListener(this.debuggerListener);
        }

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
        this.debuggerLineHighlights.sourceChanged();
    }

    public void showExecutionLine(int displayedLine) {
        if (displayedLine < 1) {
            throw new IllegalArgumentException("Displayed source line must be positive");
        }
        try {
            int lineOffset = this.editorPane.getLineStartOffset(displayedLine - 1);
            this.editorPane.setCaretPosition(lineOffset);
            DebuggerExecutionLine.show(this.debuggerLineHighlights, displayedLine);
            centerViewportOnOffset(lineOffset);
        } catch (BadLocationException exception) {
            throw new IllegalArgumentException("Source has no displayed line " + displayedLine, exception);
        }
    }

    private void clearExecutionLine() {
        DebuggerExecutionLine.clear(this.debuggerLineHighlights);
    }

    @Override
    protected void applyTheme() {
        super.applyTheme();
        if (this.debuggerLineHighlights != null) {
            this.debuggerLineHighlights.setPalette(ThemeManager.palette());
        }
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
            this.breakpointEditor.hide();
            if (this.debuggerListener != null) {
                CompanionApp.getDebuggerController().removeListener(this.debuggerListener);
            }
            if (this.breakpointMarkers != null) {
                this.editorChromeLayerUI.setBreakpointMarkers(null);
                this.breakpointMarkers.dispose();
            }
            DebuggerExecutionLine.clear(this.debuggerLineHighlights);
            this.debuggerLineHighlights.dispose();
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
        AbstractAction toggleBreakpoint = new AbstractAction("Toggle Breakpoint", Icons.BREAKPOINT) {
            @Override
            public void actionPerformed(ActionEvent event) {
                toggleBreakpointAtCaret();
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
        if (this.debugSource != null) {
            this.editorPane.getActionMap().put(TOGGLE_BREAKPOINT_KEY, toggleBreakpoint);
            this.editorPane.getInputMap(JComponent.WHEN_FOCUSED).put(
                    TOGGLE_BREAKPOINT_SHORTCUT,
                    TOGGLE_BREAKPOINT_KEY
            );
        }

        var menu = this.editorPane.getPopupMenu();
        menu.addSeparator();
        var usageItem = menu.add(usages);
        usageItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F7, InputEvent.ALT_DOWN_MASK));
        var implementationItem = menu.add(implementations);
        implementationItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK));
        var baseItem = menu.add(bases);
        baseItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK));
    }

    private void toggleBreakpointAtCaret() {
        try {
            int displayedLine = this.editorPane.getLineOfOffset(this.editorPane.getCaretPosition()) + 1;
            toggleBreakpointAtLine(displayedLine);
        } catch (BadLocationException exception) {
            this.bottomInformationBar.setFailureInfoText("Unable to resolve the selected source line");
        }
    }

    private void toggleBreakpointAtLine(int displayedLine) {
        DebuggerSessionController debugger = CompanionApp.getDebuggerController();
        Optional<DebugEngine.SourceBreakpoint> request;
        try {
            DebuggerSessionController.Breakpoint existing = debugger.breakpoint(
                    this.debugSource.uri(),
                    displayedLine
            );
            request = existing == null
                    ? breakpointRequestAtLine(displayedLine, null, null)
                    : Optional.of(existing.request());
        } catch (RuntimeException exception) {
            this.bottomInformationBar.setFailureInfoText(exception.getMessage());
            return;
        }
        if (request.isEmpty()) {
            return;
        }
        debugger.toggleBreakpoint(this.debugSource, request.get())
                .whenComplete((enabled, failure) -> SwingUtilities.invokeLater(() -> {
                    if (failure != null) {
                        failure.printStackTrace(System.err);
                        String detail = failure.getMessage();
                        this.bottomInformationBar.setFailureInfoText(
                                detail == null || detail.isBlank() ? "Unable to update breakpoint" : detail
                        );
                    }
                }));
    }

    private void showBreakpointEditor(int displayedLine, Component invoker, Point location) {
        DebuggerSessionController debugger = CompanionApp.getDebuggerController();
        DebuggerSessionController.Breakpoint managed = debugger.breakpoint(
                this.debugSource.uri(),
                displayedLine
        );
        Optional<DebugEngine.SourceBreakpoint> current;
        try {
            current = managed == null
                    ? breakpointRequestAtLine(displayedLine, null, null)
                    : Optional.of(managed.request());
        } catch (RuntimeException exception) {
            this.bottomInformationBar.setFailureInfoText(exception.getMessage());
            return;
        }
        if (current.isEmpty()) {
            return;
        }
        DebugEngine.SourceBreakpoint template = current.get();
        this.breakpointEditor.open(
                invoker,
                location,
                displayedLine,
                template,
                managed != null,
                new BreakpointEditorPopup.Handler() {
                    @Override
                    public void save(int line, String condition, String hitCount) {
                        configureBreakpoint(
                                debugger,
                                template.withConditions(condition, hitCount)
                        );
                    }

                    @Override
                    public void remove(int line) {
                        toggleBreakpointAtLine(line);
                    }
                }
        );
    }

    private void configureBreakpoint(
            DebuggerSessionController debugger,
            DebugEngine.SourceBreakpoint request
    ) {
        debugger.configureBreakpoint(this.debugSource, request)
                .whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
                    if (failure != null) {
                        failure.printStackTrace(System.err);
                        String detail = failure.getMessage();
                        this.bottomInformationBar.setFailureInfoText(
                                detail == null || detail.isBlank() ? "Unable to update breakpoint" : detail
                        );
                    }
                }));
    }

    private Optional<DebugEngine.SourceBreakpoint> breakpointRequestAtLine(
            int displayedLine,
            String condition,
            String hitCount
    ) {
        String source = ASTCache.getContents(this.identifier);
        var unit = ASTCache.getFromCache(this.identifier);
        if (source == null || unit == null) {
            throw new IllegalStateException("Source analysis is still loading");
        }
        int lineStart;
        int lineEnd;
        try {
            lineStart = this.editorPane.getLineStartOffset(displayedLine - 1);
            lineEnd = this.editorPane.getLineEndOffset(displayedLine - 1);
        } catch (BadLocationException exception) {
            throw new IllegalArgumentException("Source has no displayed line " + displayedLine, exception);
        }

        SourceDeclaration methodDeclaration = SourceDeclarationAnalyzer.analyze(unit, source).stream()
                .filter(declaration -> declaration.symbol() instanceof CodeSymbol.MethodSymbol)
                .filter(declaration -> declaration.markerOffset() >= lineStart
                        && declaration.markerOffset() < lineEnd)
                .findFirst()
                .orElse(null);
        if (methodDeclaration == null) {
            if (!this.debugSource.lineMap().isEmpty()
                    && !this.debugSource.lineMap().containsDisplayedLine(displayedLine)) {
                return Optional.empty();
            }
            return Optional.of(new DebugEngine.SourceBreakpoint(displayedLine, condition, hitCount, null));
        }
        if (this.debugSource.lineMap().isEmpty()) {
            return Optional.of(new DebugEngine.SourceBreakpoint(displayedLine, condition, hitCount, null));
        }

        CodeSymbol.MethodSymbol method = (CodeSymbol.MethodSymbol) methodDeclaration.symbol();
        int methodEndLine;
        try {
            int lastMethodOffset = Math.max(
                    methodDeclaration.markerOffset(),
                    methodDeclaration.endOffset() - 1
            );
            methodEndLine = this.editorPane.getLineOfOffset(lastMethodOffset) + 1;
        } catch (BadLocationException exception) {
            throw new IllegalStateException("Unable to resolve method source range", exception);
        }
        var debuggerLine = this.debugSource.lineMap().firstMappedDisplayedLine(displayedLine, methodEndLine);
        if (debuggerLine.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(DebugEngine.SourceBreakpoint.methodEntry(
                displayedLine,
                debuggerLine.getAsInt(),
                new DebugEngine.MethodTarget(
                        method.ownerClassName(),
                        method.name(),
                        method.descriptor()
                ),
                condition,
                hitCount
        ));
    }

    private void updateBreakpointMarkers(DebuggerSessionController debugger) {
        List<DebuggerSessionController.Breakpoint> breakpoints = debugger.breakpoints(this.debugSource.uri());
        this.breakpointMarkers.setBreakpoints(breakpoints);
        this.debuggerLineHighlights.setBreakpoints(breakpoints);
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
        MainWindow.INSTANCE.navigation().navigate(new NavigationTarget.SymbolUsages(symbol));
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
