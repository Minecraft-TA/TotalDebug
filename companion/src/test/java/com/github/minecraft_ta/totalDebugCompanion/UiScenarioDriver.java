package com.github.minecraft_ta.totalDebugCompanion;

import java.io.UncheckedIOException;
import java.io.IOException;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItem;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.CompletionItemKind;
import com.github.minecraft_ta.totalDebugCompanion.jdt.completion.SignatureHelp;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.model.UsagesView;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.CloseButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.JavaExpressionField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.DebuggerEditorPresentation;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ImageViewPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ScriptPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ScriptProblemsPanel;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totaldebug.evaluation.CompilationDiagnostic;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.SearchHeaderBar;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyFileJTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyTreeNode;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.BasePopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.CodeCompletionPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.FileNamePopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.EvaluateExpressionWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.HierarchyPreviewPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.ImplementationChooserPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.PrismInstancePicker;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.SearchEverywherePopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.SettingsWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.SignatureHelpPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.BreakpointsWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.BreakpointsWindowPreview;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger.DebuggerWindowPreview;

import org.eclipse.jdt.core.Flags;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.IconRowHeader;
import org.fife.ui.rtextarea.LineNumberList;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.tools.Diagnostic;
import javax.swing.tree.TreePath;

/** Applies one named UI state, waits until it is stable, and optionally captures it. */
final class UiScenarioDriver {
    private static ScriptView scriptView(MainWindow window, String name) {
        try {
            var files = window.editorContext().project().scriptFiles();
            var path = files.root().resolve(name + ".tdscript");
            if (!Files.exists(path)) files.create(files.root(), name, false, "");
            return new ScriptView(window.editorContext(), path);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private final int READY_POLLS = 2;
    private final int TREE_READY_POLLS = 4;

    private final MainWindow mainWindow;
    private final IntConsumer exit;
    UiScenarioDriver(MainWindow mainWindow, IntConsumer exit) {
        this.mainWindow = mainWindow;
        this.exit = exit;
    }

    void schedule(UiRenderScenario scenario, String source, Path screenshot) {
        ScenarioContext context = new ScenarioContext(source);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        int[] stableReadyPolls = {0};
        Timer timer = new Timer(50, event -> {
            try {
                advance(scenario, context);
                if (context.workspaceReady() && ready(scenario, context)) {
                    stableReadyPolls[0]++;
                    if (stableReadyPolls[0] < READY_POLLS) {
                        return;
                    }
                    ((Timer) event.getSource()).stop();
                    if (screenshot != null) {
                        capture(screenshot);
                        mainWindow.dispose();
                        exit.accept(0);
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
                mainWindow.dispose();
                exit.accept(2);
            }
        });
        timer.setInitialDelay(0);
        timer.start();
    }

    private void advance(UiRenderScenario scenario, ScenarioContext context) throws Exception {
        expandTree();
        switch (scenario) {
            case MAIN -> selectCodeEditor(context);
            case COMPLETION_MODIFIERS, COMPLETION_CASTS, SIGNATURE_HELP -> {
                selectCodeEditor(context);
                var editor = findComponent(mainWindow, RSyntaxTextArea.class);
                if (editor == null) break;
                context.once("completion-detail", () -> {
                    if (scenario == UiRenderScenario.SIGNATURE_HELP) {
                        var popup = new SignatureHelpPopup(mainWindow);
                        popup.setFont(editor.getFont());
                        popup.apply(new SignatureHelp(0, 1, List.of(
                                new SignatureHelp.Signature("ItemStack", List.of("ItemLike item", "int count"), "", false, true),
                                new SignatureHelp.Signature("ItemStack", List.of("ItemLike item", "int count", "PatchedDataComponentMap components"), "", false, false))));
                        popup.show(editor, 0, 90);
                    } else if (scenario == UiRenderScenario.COMPLETION_CASTS) {
                        var popup = new CodeCompletionPopup(mainWindow);
                        popup.setFont(editor.getFont());
                        popup.setToken("listen");
                        var items = new ArrayList<CompletionItem>();
                        String[][] rows = {{"listeners", "", "ConcurrentHashMap<Object, List<EventListener>>"},
                                {"listenerLists", "", "LockHelper<Class<?>, ListenerList>"},
                                {"addListener", "(EventPriority priority, Predicate<? super T> filter, Class<T> eventClass, Consumer<T> consumer)", "void"}};
                        for (int i = 0; i < rows.length; i++) {
                            var item = new CompletionItem(null) {
                                @Override public String getCastType() { return "EventBus"; }
                            };
                            item.setPresentation(rows[i][0], rows[i][1], rows[i][2]);
                            item.setKind(i < 2 ? CompletionItemKind.FIELD : CompletionItemKind.METHOD);
                            item.setModifiers(Flags.AccPrivate | (i < 2 ? Flags.AccFinal : 0));
                            items.add(item);
                        }
                        popup.setItems(items);
                        popup.show(editor, 0, 90);
                    } else {
                        var popup = new CodeCompletionPopup(mainWindow);
                        popup.setFont(editor.getFont());
                        popup.setToken("co");
                        var items = new ArrayList<CompletionItem>();
                        String[][] rows = {{"count", "", "int"}, {"EMPTY", "", "ItemStack"},
                                {"components", "", "PatchedDataComponentMap"}, {"CACHE", "", "Map"},
                                {"DEFAULT_SIZE", "", "int"}, {"SHARED_CACHE", "", "Map"},
                                {"copyWithCount", "(int count)", "ItemStack"}, {"consume", "(int amount, LivingEntity entity)", "void"}};
                        int[] flags = {Flags.AccPrivate, Flags.AccPublic | Flags.AccStatic | Flags.AccFinal,
                                Flags.AccPrivate | Flags.AccFinal, Flags.AccPrivate | Flags.AccStatic | Flags.AccFinal,
                                Flags.AccProtected | Flags.AccStatic | Flags.AccFinal, Flags.AccStatic | Flags.AccFinal,
                                Flags.AccPublic, Flags.AccPublic};
                        for (int i = 0; i < rows.length; i++) {
                            var item = new CompletionItem(null);
                            item.setKind(i < 6 ? CompletionItemKind.FIELD : CompletionItemKind.METHOD);
                            item.setPresentation(rows[i][0], rows[i][1], rows[i][2]);
                            item.setModifiers(flags[i]);
                            items.add(item);
                        }
                        popup.setItems(items);
                        popup.show(editor, 0, 90);
                    }
                });
            }
            case COMPLETION_SINGLE, COMPLETION_SHORTLIST -> {
                selectCodeEditor(context);
                var editor = findComponent(mainWindow, RSyntaxTextArea.class);
                if (editor == null) break;
                context.once("completion", () -> {
                    var popup = new CodeCompletionPopup(mainWindow);
                    popup.setFont(editor.getFont());
                    var item = new CompletionItem(null);
                    item.setKind(CompletionItemKind.METHOD);
                    item.setPresentation("fillReport", "(Minecraft minecraft, ClientLevel level, Options options, CrashReport report)", "CrashReport");
                    popup.setItems(Collections.nCopies(scenario == UiRenderScenario.COMPLETION_SINGLE ? 1 : 2, item));
                    popup.show(editor, 0, 90, BasePopup.Alignment.BOTTOM_RIGHT);
                });
            }
            case NEW_SCRIPT, NEW_SCRIPT_INVALID -> {
                selectCodeEditor(context);
                context.once("new-script", () -> {
                    var dialog = mainWindow.scriptFileActions().creationPopup(mainWindow.editorContext().project().paths().scripts(), false);
                    findComponent(dialog, JTextField.class).setText(
                            scenario == UiRenderScenario.NEW_SCRIPT ? "" : "My Script");
                    dialog.setLocation(mainWindow.getX() + (mainWindow.getWidth() - dialog.getWidth()) / 2,
                            mainWindow.getY() + (mainWindow.getHeight() - dialog.getHeight()) / 2);
                    dialog.setVisible(true);
                });
            }
            case SCRIPT_TOOLBAR -> context.once("script-toolbar", () ->
                    mainWindow.getEditorTabs().openEditorTab(scriptView(mainWindow, "MyScript")));
            case SCRIPT_PROBLEMS, SCRIPT_PROBLEMS_OUTDATED -> context.once("script-problems", () -> {
                var view = scriptView(mainWindow, "ProblemExample");
                mainWindow.getEditorTabs().openEditorTab(view);
                var panel = (ScriptPanel) view.getComponent();
                var editor = findComponent(panel, RSyntaxTextArea.class);
                String text = "int count = \"three\";\nint total = missingValue;\n";
                editor.setText(text);
                var generated = JavaSnippetSource.body("ProblemExample", text);
                int first = generated.source().indexOf("\"three\""), second = generated.source().indexOf("missingValue");
                var diagnostics = List.of(
                        new CompilationDiagnostic(Diagnostic.Kind.ERROR, "compiler.err.prob.found.req",
                                "Incompatible types: String cannot be converted to int", first, first + 7, 1, 1),
                        new CompilationDiagnostic(Diagnostic.Kind.ERROR, "compiler.err.cant.resolve",
                                "Cannot find symbol: missingValue", second, second + 12, 1, 1));
                try {
                    var id = ScriptPanel.class.getDeclaredField("scriptId"); id.setAccessible(true);
                    var accept = ScriptPanel.class.getDeclaredMethod("acceptResult", ExecutionResultMessage.class,
                            JavaSnippetSource.GeneratedSource.class, List.class);
                    accept.setAccessible(true);
                    accept.invoke(panel, new ExecutionResultMessage(id.getInt(panel),
                            ExecutionResult.fromStatus(ExecutionStatus.COMPILATION_FAILED, "Compilation failed")), generated, diagnostics);
                    if (scenario == UiRenderScenario.SCRIPT_PROBLEMS_OUTDATED) SwingUtilities.invokeLater(() ->
                            editor.setText(text.replace("\"three\"", "3")));
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            });
            case IMAGE_TOOLBAR -> {
                var panel = findComponent(mainWindow, ImageViewPanel.class);
                if (panel == null) break;
                context.completedActions.add("image-toolbar");
            }
            case EDITOR_FIND_TOOLBAR -> {
                selectCodeEditor(context);
                var editor = findComponent(mainWindow, RSyntaxTextArea.class);
                if (editor == null) break;
                context.once("open-editor-find", () -> {
                    var key = editor.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                            .get(KeyStroke.getKeyStroke("ctrl F"));
                    editor.getActionMap().get(key).actionPerformed(new ActionEvent(editor, 0, ""));
                });
                var header = findComponent(mainWindow, SearchHeaderBar.class);
                if (header == null) break;
                context.once("editor-find-toolbar", () -> {
                    var field = findComponent(header, FlatIconTextField.class);
                    field.setText("Theme");
                    var option = findComponent(header, FlatIconButton.class);
                    pressSpace(option);
                    if (!option.isSelected()) throw new IllegalStateException("Find option did not toggle with Space");
                });
            }
            case PROJECTS -> {
                selectCodeEditor(context);
                context.once("project-menu", () -> {
                    var menu = mainWindow.getJMenuBar().getMenu(0);
                    OffscreenPopupFactory.expectAt(menu, new Point(0, menu.getHeight()));
                    menu.doClick(0);
                });
            }
            case PRISM -> {
                selectCodeEditor(context);
                context.once("prism-picker", () -> {
                    var menu = mainWindow.getJMenuBar().getMenu(0);
                    OffscreenPopupFactory.expectAt(menu, new Point(0, menu.getHeight()));
                    menu.doClick(0);
                    SwingUtilities.invokeLater(() -> menu.getItem(1).doClick(0));
                });
            }
            case INACTIVE_TABS -> context.once("select-resource", () -> {
                int lastTab = mainWindow.getEditorTabs().getTabCount() - 1;
                mainWindow.getEditorTabs().setSelectedIndex(lastTab);
            });
            case TAB_HOVER -> advanceTabHover(context);
            case TAB_MENU, TAB_REVEAL -> {
                var tabs = mainWindow.getEditorTabs();
                context.once("select-resource", () -> tabs.setSelectedIndex(tabs.getTabCount() - 1));
                context.once("tab-menu", () -> {
                    Component header = tabs.getTabComponentAt(0);
                    Point location = new Point(8, header.getHeight());
                    OffscreenPopupFactory.expectAt(header, location);
                    header.dispatchEvent(new MouseEvent(header, MouseEvent.MOUSE_RELEASED,
                            System.currentTimeMillis(), 0, location.x, location.y, 1, true, MouseEvent.BUTTON3));
                });
                if (scenario == UiRenderScenario.TAB_REVEAL && visibleMenuPopup() != null) {
                    context.once("reveal-tab", () -> {
                        for (Component item : visibleMenuPopup().getComponents()) {
                            if (item instanceof JMenuItem action && "Reveal in tree".equals(action.getText())) {
                                action.doClick();
                                return;
                            }
                        }
                        throw new IllegalStateException("Source tab has no reveal action");
                    });
                }
            }
            case EDITOR_CURRENT_LINE -> {
                selectCodeEditor(context);
                RSyntaxTextArea editor = findComponent(mainWindow, RSyntaxTextArea.class);
                if (editor != null) {
                    int offset = context.source().indexOf("double ratio");
                    if (editor.getDocument().getLength() >= offset && editor.getCaretPosition() != offset) {
                        editor.setCaretPosition(offset);
                    }
                }
            }
            case BREAKPOINT_EDITOR -> advanceBreakpointEditor(context);
            case BREAKPOINT_INTERACTION -> advanceBreakpointInteraction(context);
            case METHOD_BREAKPOINT -> advanceMethodBreakpoint(context);
            case DEBUGGER_LOCATION -> {
                selectCodeEditor(context);
                context.once("show-debugger-location", () -> {
                    var selected = mainWindow.getEditorTabs().getSelectedEditor();
                    if (selected instanceof CodeView codeView) {
                        int line = context.source().substring(0, context.source().indexOf("double ratio"))
                                .split("\\n", -1).length;
                        codeView.showExecutionLine(line);
                        DebugEngine.StackFrame frame = new DebugEngine.StackFrame(
                                1,
                                "ThemeSample.describe",
                                "sample.ThemeSample",
                                URI.create("decompiled:///sample/ThemeSample.java"),
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
            case DEBUGGER, DEBUGGER_TOOLBAR, DEBUGGER_FRAMES_MENU, DEBUGGER_VALUES_MENU -> {
                context.once("open-debugger", () -> DebuggerWindowPreview.open(mainWindow));
                var window = findShowingWindow(DebuggerWindow.class);
                if (window == null || scenario == UiRenderScenario.DEBUGGER) break;
                if (scenario == UiRenderScenario.DEBUGGER_TOOLBAR) {
                    context.once("debugger-toolbar", () -> {
                        var mute = findComponent(window, JToggleButton.class);
                        pressSpace(mute);
                        mute.putClientProperty("JComponent.focusOwner", (Predicate<JComponent>) component -> true);
                        if (!mute.isSelected()) throw new IllegalStateException("Mute did not toggle with Space");
                    });
                    break;
                }
                if (scenario == UiRenderScenario.DEBUGGER_VALUES_MENU) {
                    JTree tree = findComponent(window, JTree.class);
                    context.once("open-value-menu", () -> {
                        tree.setSelectionRow(1);
                        var bounds = tree.getRowBounds(1);
                        OffscreenPopupFactory.expectAt(tree, new Point(bounds.x, bounds.y + bounds.height));
                        tree.getActionMap().get("rowMenu").actionPerformed(new ActionEvent(tree, 0, ""));
                    });
                    break;
                }
                JList<?> frames = findComponent(window, JList.class);
                context.once("open-frame-menu", () -> {
                    frames.requestFocusInWindow();
                    var bounds = frames.getCellBounds(frames.getSelectedIndex(), frames.getSelectedIndex());
                    OffscreenPopupFactory.expectAt(frames, new Point(bounds.x, bounds.y + bounds.height));
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(frames,
                            new KeyEvent(frames, KeyEvent.KEY_PRESSED,
                                    System.currentTimeMillis(), KeyEvent.SHIFT_DOWN_MASK,
                                    KeyEvent.VK_F10, KeyEvent.CHAR_UNDEFINED));
                });
            }
            case BREAKPOINTS, BREAKPOINTS_MENU, BREAKPOINTS_SIMPLE -> {
                context.once("open-breakpoints", () -> BreakpointsWindowPreview.open(mainWindow));
                var window = findShowingWindow(BreakpointsWindow.class);
                if (window == null || scenario == UiRenderScenario.BREAKPOINTS) break;
                var list = findComponent(window, JList.class);
                context.once("breakpoint-list-action", () -> {
                    if (scenario == UiRenderScenario.BREAKPOINTS_SIMPLE) {
                        list.setSelectedIndex(1);
                    } else {
                        list.requestFocusInWindow();
                        var bounds = list.getCellBounds(0, 0);
                        OffscreenPopupFactory.expectAt(list, new Point(bounds.x, bounds.y + bounds.height));
                        KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(list,
                                new KeyEvent(list, KeyEvent.KEY_PRESSED,
                                        System.currentTimeMillis(), KeyEvent.SHIFT_DOWN_MASK,
                                        KeyEvent.VK_F10, KeyEvent.CHAR_UNDEFINED));
                    }
                });
            }
            case EVALUATE_CODE, EVALUATE_EXPRESSION -> context.once("open-evaluate", () -> {
                var window = new EvaluateExpressionWindow(
                        mainWindow, null, mainWindow.editorContext(), mainWindow.scriptFileActions()); // This fixture renders the editor without an execution backend.
                var editor = findComponent(window, JavaExpressionField.class);
                editor.setText(scenario == UiRenderScenario.EVALUATE_CODE
                        ? "var values = java.util.List.of(1, 2, 3);\nint total = 0;\nfor (int value : values) {\n    total += value;\n}\nreturn total;"
                        : "getServer()");
                window.showWindow();
                window.setLocation(mainWindow.getX() + 70, mainWindow.getY() + 70);
            });
            case HIERARCHY_ONE -> advanceHierarchyPreview(
                    context,
                    context.source().indexOf("interface SingleAction")
            );
            case HIERARCHY_MANY -> advanceHierarchyPreview(
                    context,
                    context.source().indexOf("public interface ThemeSample")
            );
            case IMPLEMENTATION_CHOOSER, IMPLEMENTATION_MENU -> {
                advanceImplementationChooser(context);
                var popup = findShowingWindow(ImplementationChooserPopup.class);
                JList<?> list = popup == null ? null : findComponent(popup, JList.class);
                if (scenario == UiRenderScenario.IMPLEMENTATION_MENU && list != null && list.getModel().getSize() > 0) {
                    context.once("implementation-menu", () -> {
                        list.setSelectedIndex(0);
                        var bounds = list.getCellBounds(0, 0);
                        OffscreenPopupFactory.expectAt(list, new Point(bounds.x, bounds.y + bounds.height));
                        var editor = findComponent(mainWindow, RSyntaxTextArea.class);
                        KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(editor,
                                new KeyEvent(editor, KeyEvent.KEY_PRESSED,
                                        System.currentTimeMillis(), KeyEvent.SHIFT_DOWN_MASK,
                                        KeyEvent.VK_F10, KeyEvent.CHAR_UNDEFINED));
                    });
                }
            }
            case SEARCH_EMPTY, SEARCH_RESULTS, SEARCH_MENU, MODULE_FILTER -> advanceSearch(scenario, context);
            case FILE_MENU -> {
                LazyFileJTree tree = findComponent(mainWindow, LazyFileJTree.class);
                for (int row = 0; tree != null && row < tree.getRowCount(); row++) {
                    var path = tree.getPathForRow(row);
                    if (!(path.getLastPathComponent() instanceof LazyTreeNode node)
                            || !node.getUserObject().getName().equals("ThemeSample.class")) continue;
                    context.once("file-menu", () -> {
                        tree.setSelectionPath(path);
                        var bounds = tree.getPathBounds(path);
                        OffscreenPopupFactory.expectAt(tree, new Point(bounds.x, bounds.y + bounds.height));
                        tree.getActionMap().get("rowMenu").actionPerformed(new ActionEvent(tree, 0, ""));
                    });
                    break;
                }
            }
            case USAGES_RESULTS, USAGES_SEARCH, USAGES_MENU -> {
                context.once("open-usages", () -> {
                    UsagesView view = new UsagesView(mainWindow.editorContext(), new CodeSymbol.ClassSymbol("sample.ThemeSample"), mainWindow.editorContext().project().runtime());
                    mainWindow.getEditorTabs().openEditorTab(view)
                            .thenRun(() -> SwingUtilities.invokeLater(view::restartSearch));
                });
                if (scenario == UiRenderScenario.USAGES_RESULTS) break;
                var selected = mainWindow.getEditorTabs().getSelectedEditor();
                var tree = selected instanceof UsagesView
                        ? findComponent((Container) selected.getComponent(), JTree.class) : null;
                if (tree == null) break;
                for (int row = 0; row < tree.getRowCount(); row++) {
                    var path = tree.getPathForRow(row);
                    if (!path.getLastPathComponent().toString().startsWith("apply(")) continue;
                    context.once("usages-interaction", () -> {
                        tree.setSelectionPath(path);
                        tree.requestFocusInWindow();
                        var keyboard = KeyboardFocusManager.getCurrentKeyboardFocusManager();
                        if (scenario == UiRenderScenario.USAGES_SEARCH) {
                            tree.collapsePath(path.getParentPath());
                            for (char character : "apply".toCharArray()) {
                                keyboard.redispatchEvent(tree, new KeyEvent(tree,
                                        KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0,
                                        KeyEvent.VK_UNDEFINED, character));
                            }
                        } else {
                            var bounds = tree.getPathBounds(path);
                            OffscreenPopupFactory.expectAt(tree, new Point(bounds.x, bounds.y + bounds.height));
                            keyboard.redispatchEvent(tree, new KeyEvent(tree,
                                    KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                                    KeyEvent.SHIFT_DOWN_MASK, KeyEvent.VK_F10,
                                    KeyEvent.CHAR_UNDEFINED));
                        }
                    });
                    break;
                }
            }
            case SETTINGS -> {
                selectCodeEditor(context);
                context.once("open-settings", () -> {
                    SettingsWindow settings = new SettingsWindow(mainWindow, mainWindow.editorContext().project().state(), mainWindow.editorContext().debugger());
                    settings.setLocation(mainWindow.getX() + 250, mainWindow.getY() + 80);
                    settings.setVisible(true);
                });
            }
            case SERVICE_STATUS -> advanceServiceStatus(context);
            case INDEXING -> {
                selectCodeEditor(context);
                context.once("indexing", () -> mainWindow.setRuntimeIndexStatus(
                        new RuntimeIndexService.Status(
                                RuntimeIndexService.Phase.BUILDING,
                                "Building class index",
                                null
                        )
                ));
            }
        }
    }

    private DebugEngine.Variable variable(
            String name,
            String value,
            String type,
            DebugEngine.VariableKind kind
    ) {
        return new DebugEngine.Variable(name, name, name, value, type, kind, 0, 0, 0, 0);
    }

    private boolean ready(UiRenderScenario scenario, ScenarioContext context) {
        return switch (scenario) {
            case TAB_MENU -> visibleMenuPopup() != null && mainWindow.getEditorTabs().getSelectedIndex()
                    == mainWindow.getEditorTabs().getTabCount() - 1;
            case TAB_REVEAL -> {
                LazyFileJTree tree = findComponent(mainWindow, LazyFileJTree.class);
                yield tree != null && tree.getSelectionPath() != null
                        && tree.getSelectionPath().getLastPathComponent() instanceof LazyTreeNode node
                        && "ThemeSample.class".equals(node.getUserObject().getName())
                        && mainWindow.getEditorTabs().getSelectedIndex() == mainWindow.getEditorTabs().getTabCount() - 1;
            }
            case MAIN -> mainWindow.getEditorTabs().getSelectedIndex() == 0;
            case COMPLETION_SINGLE, COMPLETION_SHORTLIST, COMPLETION_MODIFIERS, COMPLETION_CASTS -> Arrays.stream(mainWindow.getOwnedWindows())
                    .anyMatch(window -> window instanceof CodeCompletionPopup
                            && window.isShowing());
            case SIGNATURE_HELP -> Arrays.stream(mainWindow.getOwnedWindows())
                    .anyMatch(window -> window instanceof SignatureHelpPopup && window.isShowing());
            case NEW_SCRIPT, NEW_SCRIPT_INVALID -> Arrays.stream(mainWindow.getOwnedWindows())
                    .anyMatch(window -> window instanceof FileNamePopup
                            && window.isShowing());
            case SCRIPT_TOOLBAR -> mainWindow.getEditorTabs().getSelectedEditor()
                    instanceof ScriptView;
            case SCRIPT_PROBLEMS, SCRIPT_PROBLEMS_OUTDATED -> findComponent(mainWindow, ScriptProblemsPanel.class) != null;
            case IMAGE_TOOLBAR -> context.completedActions.contains("image-toolbar");
            case EDITOR_FIND_TOOLBAR -> context.completedActions.contains("editor-find-toolbar");
            case DEBUGGER_TOOLBAR -> context.completedActions.contains("debugger-toolbar");
            case PROJECTS -> mainWindow.getJMenuBar().getMenu(0).isPopupMenuVisible();
            case PRISM -> Arrays.stream(mainWindow.getOwnedWindows())
                    .filter(PrismInstancePicker.class::isInstance).map(PrismInstancePicker.class::cast)
                    .anyMatch(picker -> picker.isShowing() && !picker.isLoading());
            case INACTIVE_TABS -> mainWindow.getEditorTabs().getSelectedIndex()
                    == mainWindow.getEditorTabs().getTabCount() - 1;
            case TAB_HOVER -> {
                Component header = mainWindow.getEditorTabs().getTabComponentAt(0);
                CloseButton close = header instanceof Container container
                        ? findComponent(container, CloseButton.class)
                        : null;
                yield header != null && !header.isOpaque() && close != null && close.isVisible();
            }
            case EDITOR_CURRENT_LINE -> {
                RSyntaxTextArea editor = findComponent(mainWindow, RSyntaxTextArea.class);
                IconRowHeader gutter = findComponent(mainWindow, IconRowHeader.class);
                yield editor != null && gutter != null
                        && editor.getCaretPosition() == context.source().indexOf("double ratio")
                        && gutter.isShowing();
            }
            case BREAKPOINT_EDITOR -> visibleMenuPopup() != null
                    && findLabelContaining(visibleMenuPopup(), "Line breakpoint") != null;
            case BREAKPOINT_INTERACTION -> context.completedActions.contains("breakpoint-interaction");
            case METHOD_BREAKPOINT -> {
                var selected = mainWindow.getEditorTabs().getSelectedEditor();
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
                        .map(source -> mainWindow.editorContext().debugger()
                                .breakpoint(source.uri(), declarationLine))
                        .map(breakpoint -> breakpoint.request().isMethodEntry()
                                && breakpoint.request().debuggerLine() == entryLine
                                && breakpoint.request().method().descriptor()
                                        .equals("(IZ)Ljava/util/List;"))
                        .orElse(false);
            }
            case DEBUGGER_LOCATION -> mainWindow.getEditorTabs().getSelectedEditor() instanceof CodeView;
            case DEBUGGER -> findShowingWindow(DebuggerWindow.class) != null;
            case DEBUGGER_FRAMES_MENU -> visibleMenuPopup() != null;
            case DEBUGGER_VALUES_MENU -> visibleMenuPopup() != null;
            case BREAKPOINTS -> findShowingWindow(BreakpointsWindow.class) != null;
            case BREAKPOINTS_MENU -> visibleMenuPopup() != null;
            case BREAKPOINTS_SIMPLE -> context.completedActions.contains("breakpoint-list-action");
            case EVALUATE_CODE, EVALUATE_EXPRESSION -> findShowingWindow(EvaluateExpressionWindow.class) != null;
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
            case SEARCH_MENU, IMPLEMENTATION_MENU, FILE_MENU -> visibleMenuPopup() != null;
            case USAGES_RESULTS -> {
                var selected = mainWindow.getEditorTabs().getSelectedEditor();
                JTree tree = selected instanceof UsagesView
                        ? findComponent((Container) selected.getComponent(), JTree.class)
                        : null;
                yield tree != null && tree.getRowCount() > 0;
            }
            case USAGES_SEARCH -> {
                var selected = mainWindow.getEditorTabs().getSelectedEditor();
                var tree = selected instanceof UsagesView
                        ? findComponent((Container) selected.getComponent(), JTree.class) : null;
                yield tree != null && !SpeedSearch
                        .matchingRanges(tree, "apply").isEmpty();
            }
            case USAGES_MENU -> visibleMenuPopup() != null;
            case SETTINGS -> findShowingWindow(SettingsWindow.class) != null;
            case SERVICE_STATUS -> visibleMenuPopup() != null
                    && findButton(mainWindow, "Game: Connected") != null
                    && findButton(mainWindow, "MCP: Listening") != null;
            case INDEXING -> findLabelContaining(mainWindow, "Building class index") != null;
        };
    }

    private void advanceTabHover(ScenarioContext context) {
        int lastTab = mainWindow.getEditorTabs().getTabCount() - 1;
        if (lastTab < 1) {
            return;
        }
        context.once("select-last-tab", () -> mainWindow.getEditorTabs().setSelectedIndex(lastTab));
        Component header = mainWindow.getEditorTabs().getTabComponentAt(0);
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

    private void advanceBreakpointEditor(ScenarioContext context) throws Exception {
        selectCodeEditor(context);
        RSyntaxTextArea editor = findComponent(mainWindow, RSyntaxTextArea.class);
        LineNumberList lineNumbers = findComponent(mainWindow, LineNumberList.class);
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

    private void advanceBreakpointInteraction(ScenarioContext context) throws Exception {
        selectCodeEditor(context);
        if (!(mainWindow.getEditorTabs().getSelectedEditor() instanceof CodeView codeView)) return;
        var editor = findComponent(mainWindow, RSyntaxTextArea.class);
        var lineNumbers = findComponent(mainWindow, LineNumberList.class);
        if (editor == null || lineNumbers == null) return;
        int offset = context.source().indexOf("double ratio");
        var row = editor.modelToView2D(offset);
        if (row == null) return;
        int line = editor.getLineOfOffset(offset) + 1;
        var source = codeView.getDebugSource().orElseThrow();
        var debugger = mainWindow.editorContext().debugger();
        int x = lineNumbers.getWidth() / 2;
        int y = (int) row.getCenterY();
        context.once("breakpoint-interaction", () -> {
            lineNumbers.dispatchEvent(new MouseEvent(lineNumbers, MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(), 0, x, y, 0, false, MouseEvent.NOBUTTON));
            for (int count = 1; count <= 7; count++) {
                boolean expected = count % 2 == 1;
                for (int eventId : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
                    lineNumbers.dispatchEvent(new MouseEvent(lineNumbers, eventId, System.currentTimeMillis(),
                            0, x, y, count, false, MouseEvent.BUTTON1));
                    if ((debugger.breakpoint(source.uri(), line) != null) != expected) {
                        throw new IllegalStateException("Gutter toggle did not match press " + count);
                    }
                    if ((lineNumbers.getToolTipText() != null) != expected) {
                        throw new IllegalStateException("Gutter presentation did not update on press " + count);
                    }
                }
            }
        });
    }

    private void advanceMethodBreakpoint(ScenarioContext context) throws Exception {
        selectCodeEditor(context);
        var selected = mainWindow.getEditorTabs().getSelectedEditor();
        if (!(selected instanceof CodeView codeView)
                || mainWindow.getEditorTabs().astCache().getSnapshot(codeView.getPath().toString()) == null) {
            return;
        }
        RSyntaxTextArea editor = findComponent(mainWindow, RSyntaxTextArea.class);
        LineNumberList lineNumbers = findComponent(mainWindow, LineNumberList.class);
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
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                0,
                Math.max(0, lineNumbers.getWidth() / 2),
                (int) row.getCenterY(),
                1,
                false,
                MouseEvent.BUTTON1
        )));
    }

    private void advanceServiceStatus(ScenarioContext context) {
        context.once("publish-service-status", () -> {
            mainWindow.setGameStatus(new ServiceStatus(
                    ServiceStatus.State.AVAILABLE,
                    "Connected",
                    "Minecraft is connected and authenticated."
            ));
            mainWindow.setMcpStatus(new ServiceStatus(
                    ServiceStatus.State.AVAILABLE,
                    "Listening",
                    "MCP is listening at http://127.0.0.1:32123/mcp"
            ));
        });
        JButton mcp = findButton(mainWindow, "MCP: Listening");
        if (mcp != null) {
            context.once("open-mcp-status", () -> {
                mcp.getModel().setRollover(true);
                OffscreenPopupFactory.expectAboveEnd(mcp);
                mcp.doClick();
            });
        }
    }

    private void advanceImplementationChooser(ScenarioContext context) {
        selectCodeEditor(context);
        RSyntaxTextArea editor = findComponent(mainWindow, RSyntaxTextArea.class);
        if (editor == null) {
            return;
        }
        if (!(mainWindow.getEditorTabs().getSelectedEditor() instanceof CodeView codeView)
                || mainWindow.getEditorTabs().astCache().getSnapshot(codeView.getPath().toString()) == null) {
            return;
        }
        context.once("open-chooser", () -> {
            editor.setCaretPosition(
                    context.source().indexOf("void apply(ThemeSample other)") + "void ".length()
            );
            Action action = editor.getActionMap().get("findImplementations");
            if (action == null) {
                throw new IllegalStateException("Find implementations action is unavailable");
            }
            action.actionPerformed(new ActionEvent(editor, 0, "findImplementations"));
        });
    }

    private void advanceHierarchyPreview(ScenarioContext context, int declarationOffset) throws Exception {
        selectCodeEditor(context);
        RSyntaxTextArea editor = findComponent(mainWindow, RSyntaxTextArea.class);
        IconRowHeader iconRow = findComponent(mainWindow, IconRowHeader.class);
        if (editor == null || iconRow == null) {
            return;
        }
        int line = editor.getLineOfOffset(declarationOffset);
        if (iconRow.getTrackingIcons(line).length == 0) {
            return;
        }
        Rectangle2D declaration = editor.modelToView2D(declarationOffset);
        var viewport = (JViewport) SwingUtilities.getAncestorOfClass(
                JViewport.class,
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

    private void advanceSearch(UiRenderScenario scenario, ScenarioContext context) {
        selectCodeEditor(context);
        context.once("open-search", mainWindow::openSearchEverywhere);
        SearchEverywherePopup popup = findShowingWindow(SearchEverywherePopup.class);
        if (popup == null) {
            return;
        }
        context.once("position-search", () -> popup.setLocation(
                mainWindow.getX() + 220,
                mainWindow.getY() + 70
        ));
        if (scenario == UiRenderScenario.SEARCH_RESULTS || scenario == UiRenderScenario.SEARCH_MENU) {
            FlatIconTextField field = findComponent(popup, FlatIconTextField.class);
            if (field != null) {
                context.once("search-query", () -> field.setText("Theme"));
            }
            JList<?> list = findComponent(popup, JList.class);
            if (scenario == UiRenderScenario.SEARCH_MENU && list != null && list.getModel().getSize() > 0
                    && list.getSelectedIndex() >= 0) {
                context.once("search-menu", () -> {
                    var bounds = list.getCellBounds(list.getSelectedIndex(), list.getSelectedIndex());
                    OffscreenPopupFactory.expectAt(list, new Point(bounds.x, bounds.y + bounds.height));
                    list.getActionMap().get("rowMenu").actionPerformed(new ActionEvent(list, 0, ""));
                });
            }
        } else if (scenario == UiRenderScenario.MODULE_FILTER) {
            JButton filter = findButton(popup, "All modules");
            if (filter != null) {
                context.once("module-filter", () -> {
                    OffscreenPopupFactory.expectBelowEnd(filter);
                    var root = popup.getRootPane();
                    var key = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                            .get(KeyStroke.getKeyStroke("alt M"));
                    root.getActionMap().get(key).actionPerformed(new ActionEvent(root, 0, ""));
                });
            }
        }
    }

    private void selectCodeEditor(ScenarioContext context) {
        context.once("select-code", () -> mainWindow.getEditorTabs().setSelectedIndex(0));
    }

    private static void pressSpace(AbstractButton button) {
        for (String stroke : new String[]{"pressed SPACE", "released SPACE"}) {
            var key = button.getInputMap().get(KeyStroke.getKeyStroke(stroke));
            button.getActionMap().get(key).actionPerformed(new ActionEvent(button, 0, stroke));
        }
    }

    private void expandTree() {
        LazyFileJTree tree = findComponent(mainWindow, LazyFileJTree.class);
        if (tree == null) {
            return;
        }
        for (int row = 0; row < tree.getRowCount(); row++) {
            tree.expandRow(row);
        }
    }

    private JPopupMenu visibleMenuPopup() {
        return Arrays.stream(MenuSelectionManager.defaultManager().getSelectedPath())
                .filter(JPopupMenu.class::isInstance)
                .map(JPopupMenu.class::cast)
                .filter(JPopupMenu::isShowing)
                .findFirst()
                .orElse(null);
    }

    private <T extends Window> T findShowingWindow(Class<T> type) {
        return Arrays.stream(Window.getWindows())
                .filter(type::isInstance)
                .map(type::cast)
                .filter(Window::isShowing)
                .findFirst()
                .orElse(null);
    }

    private <T extends Component> T findComponent(Container root, Class<T> type) {
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

    private JLabel findLabelContaining(Container root, String expectedText) {
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label
                    && label.getText() != null
                    && label.getText().contains(expectedText)) {
                return label;
            }
            if (component instanceof Container child) {
                JLabel match = findLabelContaining(child, expectedText);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private JButton findButton(Container root, String text) {
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

    private void dispatchMouseMove(Component component, Point point) {
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

    private void capture(Path target) throws Exception {
        Files.createDirectories(target.toAbsolutePath().getParent());
        BufferedImage image = new BufferedImage(
                mainWindow.getWidth(),
                mainWindow.getHeight(),
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = image.createGraphics();
        mainWindow.paintAll(graphics);
        for (Window window : Window.getWindows()) {
            if (window == mainWindow || !window.isShowing()) {
                continue;
            }
            Graphics2D popupGraphics = (Graphics2D) graphics.create();
            popupGraphics.translate(
                    window.getX() - mainWindow.getX(),
                    window.getY() - mainWindow.getY()
            );
            window.paintAll(popupGraphics);
            popupGraphics.dispose();
        }
        OffscreenPopupFactory.paintActivePopups(graphics, mainWindow);
        graphics.dispose();
        ImageIO.write(image, "png", target.toFile());
        System.out.println("UI screenshot: " + target.toAbsolutePath());
    }

    private final class ScenarioContext {
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
            LazyFileJTree tree = findComponent(mainWindow, LazyFileJTree.class);
            if (tree == null || tree.getRowCount() == 0) {
                return false;
            }
            for (int row = 0; row < tree.getRowCount(); row++) {
                TreePath path = tree.getPathForRow(row);
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
