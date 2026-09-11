package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.model.UsagesView;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.CloseButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.DebuggerEditorPresentation;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyFileJTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.HierarchyPreviewPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.ImplementationChooserPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.SearchEverywherePopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.SettingsWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerWindowPreview;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.BreakpointsWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.BreakpointsWindowPreview;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.IconRowHeader;
import org.fife.ui.rtextarea.LineNumberList;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Applies one named UI state, waits until it is stable, and optionally captures it. */
final class UiScenarioDriver {
    private static final int READY_POLLS = 2;
    private static final int TREE_READY_POLLS = 4;

    private UiScenarioDriver() {
    }

    static void schedule(UiRenderScenario scenario, String source, Path screenshot) {
        ScenarioContext context = new ScenarioContext(source);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        int[] stableReadyPolls = {0};
        javax.swing.Timer timer = new javax.swing.Timer(50, event -> {
            try {
                advance(scenario, context);
                if (context.workspaceReady() && ready(scenario, context)) {
                    stableReadyPolls[0]++;
                    if (stableReadyPolls[0] < READY_POLLS) {
                        return;
                    }
                    ((javax.swing.Timer) event.getSource()).stop();
                    if (screenshot != null) {
                        capture(screenshot);
                        MainWindow.INSTANCE.dispose();
                        System.exit(0);
                    }
                    System.out.println("UI scenario ready: " + scenario.id());
                    return;
                }
                stableReadyPolls[0] = 0;
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("UI scenario did not become ready: " + scenario.id());
                }
            } catch (Exception exception) {
                exception.printStackTrace(System.err);
                MainWindow.INSTANCE.dispose();
                System.exit(2);
            }
        });
        timer.setInitialDelay(0);
        timer.start();
    }

    private static void advance(UiRenderScenario scenario, ScenarioContext context) throws Exception {
        expandTree();
        switch (scenario) {
            case MAIN -> selectCodeEditor(context);
            case INACTIVE_TABS -> context.once("select-resource", () -> {
                int lastTab = MainWindow.INSTANCE.getEditorTabs().getTabCount() - 1;
                MainWindow.INSTANCE.getEditorTabs().setSelectedIndex(lastTab);
            });
            case TAB_HOVER -> advanceTabHover(context);
            case EDITOR_CURRENT_LINE -> {
                selectCodeEditor(context);
                RSyntaxTextArea editor = findComponent(MainWindow.INSTANCE, RSyntaxTextArea.class);
                if (editor != null) {
                    int offset = context.source().indexOf("double ratio");
                    if (editor.getDocument().getLength() >= offset && editor.getCaretPosition() != offset) {
                        editor.setCaretPosition(offset);
                    }
                }
            }
            case BREAKPOINT_EDITOR -> advanceBreakpointEditor(context);
            case METHOD_BREAKPOINT -> advanceMethodBreakpoint(context);
            case DEBUGGER_LOCATION -> {
                selectCodeEditor(context);
                context.once("show-debugger-location", () -> {
                    var selected = MainWindow.INSTANCE.getEditorTabs().getSelectedEditor();
                    if (selected instanceof CodeView codeView) {
                        int line = context.source().substring(0, context.source().indexOf("double ratio"))
                                .split("\\n", -1).length;
                        codeView.showExecutionLine(line);
                        DebugEngine.StackFrame frame = new DebugEngine.StackFrame(
                                1,
                                "ThemeSample.describe",
                                "sample.ThemeSample",
                                java.net.URI.create("decompiled:///sample/ThemeSample.java"),
                                line,
                                1
                        );
                        DebuggerEditorPresentation.select(frame, List.of(
                                variable("count", "7", "int", DebugEngine.VariableKind.PARAMETER),
                                variable("verbose", "true", "boolean", DebugEngine.VariableKind.PARAMETER),
                                variable("ratio", "2.0", "double", DebugEngine.VariableKind.LOCAL),
                                variable("marker", "'x'", "char", DebugEngine.VariableKind.LOCAL)
                        ));
                    }
                });
            }
            case DEBUGGER -> context.once("open-debugger", () -> DebuggerWindowPreview.open(MainWindow.INSTANCE));
            case BREAKPOINTS -> context.once(
                    "open-breakpoints",
                    () -> BreakpointsWindowPreview.open(MainWindow.INSTANCE)
            );
            case EVALUATE_CODE, EVALUATE_EXPRESSION -> context.once("open-evaluate", () -> {
                var window = new com.github.minecraft_ta.totalDebugCompanion.ui.views.EvaluateExpressionWindow(
                        MainWindow.INSTANCE, null); // This fixture renders the editor without an execution backend.
                var editor = findComponent(window, com.github.minecraft_ta.totalDebugCompanion.ui.components.JavaExpressionField.class);
                editor.setText(scenario == UiRenderScenario.EVALUATE_CODE
                        ? "var values = java.util.List.of(1, 2, 3);\nint total = 0;\nfor (int value : values) {\n    total += value;\n}\nreturn total;"
                        : "getServer()");
                window.showWindow();
                window.setLocation(MainWindow.INSTANCE.getX() + 70, MainWindow.INSTANCE.getY() + 70);
            });
            case HIERARCHY_ONE -> advanceHierarchyPreview(
                    context,
                    context.source().indexOf("interface SingleAction")
            );
            case HIERARCHY_MANY -> advanceHierarchyPreview(
                    context,
                    context.source().indexOf("public interface ThemeSample")
            );
            case IMPLEMENTATION_CHOOSER -> advanceImplementationChooser(context);
            case SEARCH_EMPTY, SEARCH_RESULTS, MODULE_FILTER -> advanceSearch(scenario, context);
            case USAGES_RESULTS -> context.once("open-usages", () -> {
                UsagesView view = new UsagesView(new CodeSymbol.ClassSymbol("sample.ThemeSample"), CompanionApp.currentRuntime());
                MainWindow.INSTANCE.getEditorTabs().openEditorTab(view)
                        .thenRun(() -> SwingUtilities.invokeLater(view::restartSearch));
            });
            case SETTINGS -> {
                selectCodeEditor(context);
                context.once("open-settings", () -> {
                    SettingsWindow settings = new SettingsWindow(MainWindow.INSTANCE);
                    settings.setLocation(MainWindow.INSTANCE.getX() + 250, MainWindow.INSTANCE.getY() + 80);
                    settings.setVisible(true);
                });
            }
            case SERVICE_STATUS -> advanceServiceStatus(context);
            case INDEXING -> {
                selectCodeEditor(context);
                context.once("indexing", () -> MainWindow.INSTANCE.setRuntimeIndexStatus(
                        new RuntimeIndexService.Status(
                                RuntimeIndexService.Phase.BUILDING,
                                "Building class index",
                                null
                        )
                ));
            }
        }
    }

    private static DebugEngine.Variable variable(
            String name,
            String value,
            String type,
            DebugEngine.VariableKind kind
    ) {
        return new DebugEngine.Variable(name, name, name, value, type, kind, 0, 0, 0, 0);
    }

    private static boolean ready(UiRenderScenario scenario, ScenarioContext context) {
        return switch (scenario) {
            case MAIN -> MainWindow.INSTANCE.getEditorTabs().getSelectedIndex() == 0;
            case INACTIVE_TABS -> MainWindow.INSTANCE.getEditorTabs().getSelectedIndex()
                    == MainWindow.INSTANCE.getEditorTabs().getTabCount() - 1;
            case TAB_HOVER -> {
                Component header = MainWindow.INSTANCE.getEditorTabs().getTabComponentAt(0);
                CloseButton close = header instanceof Container container
                        ? findComponent(container, CloseButton.class)
                        : null;
                yield header != null && !header.isOpaque() && close != null && close.isVisible();
            }
            case EDITOR_CURRENT_LINE -> {
                RSyntaxTextArea editor = findComponent(MainWindow.INSTANCE, RSyntaxTextArea.class);
                IconRowHeader gutter = findComponent(MainWindow.INSTANCE, IconRowHeader.class);
                yield editor != null && gutter != null
                        && editor.getCaretPosition() == context.source().indexOf("double ratio")
                        && gutter.isShowing();
            }
            case BREAKPOINT_EDITOR -> visibleMenuPopup() != null
                    && findLabelContaining(visibleMenuPopup(), "Line breakpoint") != null;
            case METHOD_BREAKPOINT -> {
                var selected = MainWindow.INSTANCE.getEditorTabs().getSelectedEditor();
                if (!(selected instanceof CodeView codeView)) {
                    yield false;
                }
                int declarationLine = context.source()
                        .substring(0, context.source().indexOf("static List<String> describe"))
                        .split("\\n", -1).length;
                int entryLine = context.source()
                        .substring(0, context.source().indexOf("double ratio"))
                        .split("\\n", -1).length;
                yield codeView.getDebugSource()
                        .map(source -> CompanionApp.getDebuggerController()
                                .breakpoint(source.uri(), declarationLine))
                        .map(breakpoint -> breakpoint.request().isMethodEntry()
                                && breakpoint.request().debuggerLine() == entryLine
                                && breakpoint.request().method().descriptor()
                                        .equals("(IZ)Ljava/util/List;"))
                        .orElse(false);
            }
            case DEBUGGER_LOCATION -> MainWindow.INSTANCE.getEditorTabs().getSelectedEditor() instanceof CodeView;
            case DEBUGGER -> findShowingWindow(DebuggerWindow.class) != null;
            case BREAKPOINTS -> findShowingWindow(BreakpointsWindow.class) != null;
            case EVALUATE_CODE, EVALUATE_EXPRESSION -> findShowingWindow(com.github.minecraft_ta.totalDebugCompanion.ui.views.EvaluateExpressionWindow.class) != null;
            case HIERARCHY_ONE, HIERARCHY_MANY -> {
                HierarchyPreviewPopup popup = findShowingWindow(HierarchyPreviewPopup.class);
                yield popup != null && findLabelContaining(popup, "Looking up") == null;
            }
            case IMPLEMENTATION_CHOOSER -> {
                ImplementationChooserPopup popup = findShowingWindow(ImplementationChooserPopup.class);
                @SuppressWarnings("rawtypes")
                JList list = popup == null ? null : findComponent(popup, JList.class);
                yield list != null && list.getModel().getSize() > 0;
            }
            case SEARCH_EMPTY -> {
                SearchEverywherePopup popup = findShowingWindow(SearchEverywherePopup.class);
                yield popup != null && findLabelContaining(popup, "Type to search") != null;
            }
            case SEARCH_RESULTS -> {
                SearchEverywherePopup popup = findShowingWindow(SearchEverywherePopup.class);
                @SuppressWarnings("rawtypes")
                JList list = popup == null ? null : findComponent(popup, JList.class);
                yield list != null && list.getModel().getSize() > 0 && list.getSelectedIndex() >= 0;
            }
            case MODULE_FILTER -> visibleMenuPopup() != null;
            case USAGES_RESULTS -> {
                var selected = MainWindow.INSTANCE.getEditorTabs().getSelectedEditor();
                javax.swing.JTree tree = selected instanceof UsagesView
                        ? findComponent((Container) selected.getComponent(), javax.swing.JTree.class)
                        : null;
                yield tree != null && tree.getRowCount() > 0;
            }
            case SETTINGS -> findShowingWindow(SettingsWindow.class) != null;
            case SERVICE_STATUS -> visibleMenuPopup() != null
                    && findButton(MainWindow.INSTANCE, "Game: Connected") != null
                    && findButton(MainWindow.INSTANCE, "MCP: Listening") != null;
            case INDEXING -> findLabelContaining(MainWindow.INSTANCE, "Building class index") != null;
        };
    }

    private static void advanceTabHover(ScenarioContext context) {
        int lastTab = MainWindow.INSTANCE.getEditorTabs().getTabCount() - 1;
        if (lastTab < 1) {
            return;
        }
        context.once("select-last-tab", () -> MainWindow.INSTANCE.getEditorTabs().setSelectedIndex(lastTab));
        Component header = MainWindow.INSTANCE.getEditorTabs().getTabComponentAt(0);
        if (header != null) {
            context.once("hover-first-tab", () -> header.dispatchEvent(new MouseEvent(
                    header,
                    MouseEvent.MOUSE_ENTERED,
                    System.currentTimeMillis(),
                    0,
                    2,
                    2,
                    0,
                    false,
                    MouseEvent.NOBUTTON
            )));
        }
    }

    private static void advanceBreakpointEditor(ScenarioContext context) throws Exception {
        selectCodeEditor(context);
        RSyntaxTextArea editor = findComponent(MainWindow.INSTANCE, RSyntaxTextArea.class);
        LineNumberList lineNumbers = findComponent(MainWindow.INSTANCE, LineNumberList.class);
        if (editor == null || lineNumbers == null) {
            return;
        }
        int offset = context.source().indexOf("double ratio");
        Rectangle2D row = editor.modelToView2D(offset);
        if (row == null) {
            return;
        }
        context.once("open-breakpoint-editor", () -> {
            OffscreenPopupFactory.expectAt(
                    lineNumbers,
                    new Point(lineNumbers.getWidth() + 6, (int) row.getY())
            );
            lineNumbers.dispatchEvent(new MouseEvent(
                    lineNumbers,
                    MouseEvent.MOUSE_RELEASED,
                    System.currentTimeMillis(),
                    0,
                    Math.max(0, lineNumbers.getWidth() / 2),
                    (int) row.getCenterY(),
                    1,
                    true,
                    MouseEvent.BUTTON3
            ));
        });
    }

    private static void advanceMethodBreakpoint(ScenarioContext context) throws Exception {
        selectCodeEditor(context);
        var selected = MainWindow.INSTANCE.getEditorTabs().getSelectedEditor();
        if (!(selected instanceof CodeView codeView)
                || ASTCache.getFromCache(codeView.getPath().toString()) == null) {
            return;
        }
        RSyntaxTextArea editor = findComponent(MainWindow.INSTANCE, RSyntaxTextArea.class);
        LineNumberList lineNumbers = findComponent(MainWindow.INSTANCE, LineNumberList.class);
        if (editor == null || lineNumbers == null) {
            return;
        }
        int offset = context.source().indexOf("static List<String> describe");
        Rectangle2D row = editor.modelToView2D(offset);
        if (row == null) {
            return;
        }
        context.once("toggle-method-breakpoint", () -> lineNumbers.dispatchEvent(new MouseEvent(
                lineNumbers,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                Math.max(0, lineNumbers.getWidth() / 2),
                (int) row.getCenterY(),
                1,
                false,
                MouseEvent.BUTTON1
        )));
    }

    private static void advanceServiceStatus(ScenarioContext context) {
        context.once("publish-service-status", () -> {
            MainWindow.INSTANCE.setGameStatus(new ServiceStatus(
                    ServiceStatus.State.AVAILABLE,
                    "Connected",
                    "Minecraft is connected and authenticated."
            ));
            MainWindow.INSTANCE.setMcpStatus(new ServiceStatus(
                    ServiceStatus.State.AVAILABLE,
                    "Listening",
                    "MCP is listening at http://127.0.0.1:32123/mcp"
            ));
        });
        JButton mcp = findButton(MainWindow.INSTANCE, "MCP: Listening");
        if (mcp != null) {
            context.once("open-mcp-status", () -> {
                mcp.getModel().setRollover(true);
                OffscreenPopupFactory.expectAboveEnd(mcp);
                mcp.doClick();
            });
        }
    }

    private static void advanceImplementationChooser(ScenarioContext context) {
        selectCodeEditor(context);
        RSyntaxTextArea editor = findComponent(MainWindow.INSTANCE, RSyntaxTextArea.class);
        if (editor == null) {
            return;
        }
        if (!(MainWindow.INSTANCE.getEditorTabs().getSelectedEditor() instanceof CodeView codeView)
                || ASTCache.getFromCache(codeView.getPath().toString()) == null) {
            return;
        }
        context.once("open-chooser", () -> {
            editor.setCaretPosition(
                    context.source().indexOf("void apply(ThemeSample other)") + "void ".length()
            );
            javax.swing.Action action = editor.getActionMap().get("findImplementations");
            if (action == null) {
                throw new IllegalStateException("Find implementations action is unavailable");
            }
            action.actionPerformed(new java.awt.event.ActionEvent(editor, 0, "findImplementations"));
        });
    }

    private static void advanceHierarchyPreview(ScenarioContext context, int declarationOffset) throws Exception {
        selectCodeEditor(context);
        RSyntaxTextArea editor = findComponent(MainWindow.INSTANCE, RSyntaxTextArea.class);
        IconRowHeader iconRow = findComponent(MainWindow.INSTANCE, IconRowHeader.class);
        if (editor == null || iconRow == null) {
            return;
        }
        int line = editor.getLineOfOffset(declarationOffset);
        if (iconRow.getTrackingIcons(line).length == 0) {
            return;
        }
        Rectangle2D declaration = editor.modelToView2D(declarationOffset);
        var viewport = (javax.swing.JViewport) SwingUtilities.getAncestorOfClass(
                javax.swing.JViewport.class,
                editor
        );
        if (viewport != null) {
            int targetY = Math.max(0, (int) declaration.getY() - viewport.getExtentSize().height / 2);
            viewport.setViewPosition(new Point(viewport.getViewPosition().x, targetY));
        }
        declaration = editor.modelToView2D(declarationOffset);
        Point target = SwingUtilities.convertPoint(
                editor,
                0,
                (int) Math.floor(declaration.getY() + declaration.getHeight() / 2),
                iconRow
        );
        target.x = iconRow.getWidth() / 2;
        dispatchMouseMove(iconRow, target);
    }

    private static void advanceSearch(UiRenderScenario scenario, ScenarioContext context) {
        selectCodeEditor(context);
        context.once("open-search", MainWindow.INSTANCE::openSearchEverywhere);
        SearchEverywherePopup popup = findShowingWindow(SearchEverywherePopup.class);
        if (popup == null) {
            return;
        }
        context.once("position-search", () -> popup.setLocation(
                MainWindow.INSTANCE.getX() + 220,
                MainWindow.INSTANCE.getY() + 70
        ));
        if (scenario == UiRenderScenario.SEARCH_RESULTS) {
            FlatIconTextField field = findComponent(popup, FlatIconTextField.class);
            if (field != null) {
                context.once("search-query", () -> field.setText("Theme"));
            }
        } else if (scenario == UiRenderScenario.MODULE_FILTER) {
            JButton filter = findButton(popup, "All modules");
            if (filter != null) {
                context.once("module-filter", () -> {
                    OffscreenPopupFactory.expectBelowEnd(filter);
                    filter.doClick();
                });
            }
        }
    }

    private static void selectCodeEditor(ScenarioContext context) {
        context.once("select-code", () -> MainWindow.INSTANCE.getEditorTabs().setSelectedIndex(0));
    }

    private static void expandTree() {
        LazyFileJTree tree = findComponent(MainWindow.INSTANCE, LazyFileJTree.class);
        if (tree == null) {
            return;
        }
        for (int row = 0; row < tree.getRowCount(); row++) {
            tree.expandRow(row);
        }
    }

    private static javax.swing.JPopupMenu visibleMenuPopup() {
        return Arrays.stream(javax.swing.MenuSelectionManager.defaultManager().getSelectedPath())
                .filter(javax.swing.JPopupMenu.class::isInstance)
                .map(javax.swing.JPopupMenu.class::cast)
                .filter(javax.swing.JPopupMenu::isShowing)
                .findFirst()
                .orElse(null);
    }

    private static <T extends java.awt.Window> T findShowingWindow(Class<T> type) {
        return Arrays.stream(java.awt.Window.getWindows())
                .filter(type::isInstance)
                .map(type::cast)
                .filter(java.awt.Window::isShowing)
                .findFirst()
                .orElse(null);
    }

    private static <T extends Component> T findComponent(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                T match = findComponent(child, type);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static javax.swing.JLabel findLabelContaining(Container root, String expectedText) {
        for (Component component : root.getComponents()) {
            if (component instanceof javax.swing.JLabel label
                    && label.getText() != null
                    && label.getText().contains(expectedText)) {
                return label;
            }
            if (component instanceof Container child) {
                javax.swing.JLabel match = findLabelContaining(child, expectedText);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static JButton findButton(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton match = findButton(child, text);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static void dispatchMouseMove(Component component, Point point) {
        long now = System.currentTimeMillis();
        component.dispatchEvent(new MouseEvent(
                component,
                MouseEvent.MOUSE_ENTERED,
                now,
                0,
                point.x,
                point.y,
                0,
                false,
                MouseEvent.NOBUTTON
        ));
        component.dispatchEvent(new MouseEvent(
                component,
                MouseEvent.MOUSE_MOVED,
                now + 1,
                0,
                point.x,
                point.y,
                0,
                false,
                MouseEvent.NOBUTTON
        ));
    }

    private static void capture(Path target) throws Exception {
        Files.createDirectories(target.toAbsolutePath().getParent());
        BufferedImage image = new BufferedImage(
                MainWindow.INSTANCE.getWidth(),
                MainWindow.INSTANCE.getHeight(),
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = image.createGraphics();
        MainWindow.INSTANCE.paintAll(graphics);
        for (java.awt.Window window : java.awt.Window.getWindows()) {
            if (window == MainWindow.INSTANCE || !window.isShowing()) {
                continue;
            }
            Graphics2D popupGraphics = (Graphics2D) graphics.create();
            popupGraphics.translate(
                    window.getX() - MainWindow.INSTANCE.getX(),
                    window.getY() - MainWindow.INSTANCE.getY()
            );
            window.paintAll(popupGraphics);
            popupGraphics.dispose();
        }
        OffscreenPopupFactory.paintActivePopups(graphics, MainWindow.INSTANCE);
        graphics.dispose();
        ImageIO.write(image, "png", target.toFile());
        System.out.println("UI screenshot: " + target.toAbsolutePath());
    }

    private static final class ScenarioContext {
        private final String source;
        private final Set<String> completedActions = new HashSet<>();
        private int previousTreeRows = -1;
        private int stableTreePolls;

        private ScenarioContext(String source) {
            this.source = source;
        }

        private String source() {
            return this.source;
        }

        private void once(String key, Runnable action) {
            if (this.completedActions.add(key)) {
                action.run();
            }
        }

        private boolean workspaceReady() {
            LazyFileJTree tree = findComponent(MainWindow.INSTANCE, LazyFileJTree.class);
            if (tree == null || tree.getRowCount() == 0) {
                return false;
            }
            for (int row = 0; row < tree.getRowCount(); row++) {
                javax.swing.tree.TreePath path = tree.getPathForRow(row);
                if (path != null && path.getLastPathComponent().toString().contains("Loading...")) {
                    this.stableTreePolls = 0;
                    this.previousTreeRows = tree.getRowCount();
                    return false;
                }
            }
            if (this.previousTreeRows == tree.getRowCount()) {
                this.stableTreePolls++;
            } else {
                this.previousTreeRows = tree.getRowCount();
                this.stableTreePolls = 0;
            }
            return this.stableTreePolls >= TREE_READY_POLLS;
        }
    }
}
