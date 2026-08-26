package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.model.UsagesView;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyFileJTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.HierarchyPreviewPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.ImplementationChooserPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.SearchEverywherePopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.SettingsWindow;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.IconRowHeader;

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
                UsagesView view = new UsagesView(new CodeSymbol.ClassSymbol("sample.ThemeSample"));
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

    private static boolean ready(UiRenderScenario scenario, ScenarioContext context) {
        return switch (scenario) {
            case MAIN -> MainWindow.INSTANCE.getEditorTabs().getSelectedIndex() == 0;
            case INACTIVE_TABS -> MainWindow.INSTANCE.getEditorTabs().getSelectedIndex()
                    == MainWindow.INSTANCE.getEditorTabs().getTabCount() - 1;
            case EDITOR_CURRENT_LINE -> {
                RSyntaxTextArea editor = findComponent(MainWindow.INSTANCE, RSyntaxTextArea.class);
                IconRowHeader gutter = findComponent(MainWindow.INSTANCE, IconRowHeader.class);
                yield editor != null && gutter != null
                        && editor.getCaretPosition() == context.source().indexOf("double ratio")
                        && gutter.isShowing();
            }
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
            case INDEXING -> findLabelContaining(MainWindow.INSTANCE, "Building class index") != null;
        };
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
                context.once("module-filter", filter::doClick);
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
        javax.swing.JPopupMenu menuPopup = visibleMenuPopup();
        if (menuPopup != null) {
            Point location = menuPopup.getLocationOnScreen();
            Component invoker = menuPopup.getInvoker();
            if (invoker != null) {
                Point invokerLocation = invoker.getLocationOnScreen();
                location = new Point(
                        invokerLocation.x + invoker.getWidth() - menuPopup.getWidth(),
                        invokerLocation.y + invoker.getHeight()
                );
            }
            Graphics2D popupGraphics = (Graphics2D) graphics.create();
            popupGraphics.translate(
                    location.x - MainWindow.INSTANCE.getX(),
                    location.y - MainWindow.INSTANCE.getY()
            );
            menuPopup.paintAll(popupGraphics);
            popupGraphics.dispose();
        }
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
