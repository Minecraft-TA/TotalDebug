package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.JavaExpressionField;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.BeforeAll;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import org.junit.jupiter.api.AfterAll;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysisFixtures;
import com.github.tth05.jindex.ClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.ExpressionCompletionSupport;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;

import javax.swing.*;
import java.lang.reflect.Field;
import java.net.URI;
import java.awt.event.ActionEvent;
import java.awt.event.WindowEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.KeyboardFocusManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@UiTest
class BreakpointsWindowTest {
    @TempDir Path directory;
    private static ClassIndex index;
    @BeforeAll static void index() throws Exception {
        ((AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance()).putMapping(SyntaxConstants.SYNTAX_STYLE_JAVA, CustomJavaTokenMaker.class.getName());
        index = JavaAnalysisFixtures.index(); CompanionClassIndex.set(index);
    }
    @AfterAll static void closeIndex() { CompanionClassIndex.clear(); index.close(); }

    @ParameterizedTest @ValueSource(strings = {"clean", "dirty", "unsaved"})
    void movingScriptFoldersRebasesSelectionsWithoutDiscardingOtherDraftFields(String form) throws Exception {
        try (var fixture = new Fixture(directory)) {
            SwingUtilities.invokeAndWait(() -> {
                var hit = field(fixture.window, "hitCount", JTextField.class);
                if (form.equals("unsaved")) hit.setText("bad");
                field(fixture.window, "actionKind", JComboBox.class).setSelectedIndex(2);
                field(fixture.window, "actionScript", JComboBox.class).setSelectedItem("nested/Debug.tdscript");
                if (form.equals("dirty")) hit.setText("bad");
                if (form.equals("unsaved")) assertNull(fixture.controller.breakpoint(fixture.source.uri(), 2).request().action());
            });
            Files.move(fixture.scriptsRoot.resolve("nested"), fixture.scriptsRoot.resolve("moved"));
            SwingUtilities.invokeAndWait(() -> {
                // An older enumeration may still be queued when the relocation is published.
                field(fixture.window, "refreshScripts", FlatIconButton.class).doClick(0);
                fixture.controller.remapScriptActions(name -> name.replace("nested/", "moved/"));
                assertEquals("moved/Debug.tdscript", field(fixture.window, "actionScript", JComboBox.class).getSelectedItem());
            });
            fixture.awaitScripts();
            SwingUtilities.invokeAndWait(() -> {
                var scripts = field(fixture.window, "actionScript", JComboBox.class);
                assertEquals("moved/Debug.tdscript", scripts.getSelectedItem());
                assertEquals(1, scripts.getItemCount());
                var hit = field(fixture.window, "hitCount", JTextField.class);
                assertEquals(form.equals("clean") ? "" : "bad", hit.getText());
                hit.setText("5");
                hit.postActionEvent();
                var request = fixture.controller.breakpoint(fixture.source.uri(), 2).request();
                assertEquals("5", request.hitCondition());
                assertEquals("moved/Debug.tdscript", request.action().script());
                assertFalse(field(fixture.window, "actionError", JLabel.class).isVisible());
            });
        }
    }

    @Test void conditionsAndInlineActionsCompleteInBreakpointScope() throws Exception {
        try (var fixture = new Fixture(directory)) {
            String source = "class Example {\n void run(int count) {\n  String label = \"ready\";\n  label.length();\n }\n}";
            fixture.controller.registerSource(new DebugEngine.Source(fixture.source.uri(), "example.Example", source));
            var provider = field(fixture.window, "currentCompletion", ExpressionCompletionSupport.CompletionProvider.class);
            assertTrue(provider.complete("cou", 3, true).get(5, TimeUnit.SECONDS).stream().anyMatch(p -> p.label().equals("count")));
            assertDoesNotThrow(() -> provider.complete("l\u00f6", 2, true).get(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> field(fixture.window, "actionKind", JComboBox.class).setSelectedIndex(1));
            var action = field(fixture.window, "currentActionCompletion", ExpressionCompletionSupport.CompletionProvider.class);
            assertTrue(action.complete("cou", 3, true).get(5, TimeUnit.SECONDS).stream().anyMatch(p -> p.label().equals("count")));
            String text = "String localText = \"ready\";\nlocalT";
            assertTrue(action.complete(text, text.length(), true).get(5, TimeUnit.SECONDS).stream().anyMatch(p -> p.label().equals("localText")));
            String member = "String localText = \"ready\";\nlocalText.len";
            var memberMatches = action.complete(member, member.length(), true).get(5, TimeUnit.SECONDS);
            assertTrue(memberMatches.stream().anyMatch(p -> p.label().startsWith("length(")), memberMatches.toString());
            assertFalse(action.complete("loc\nString localText = \"late\";", 3, true).get(5, TimeUnit.SECONDS).stream().anyMatch(p -> p.label().equals("localText")));
            SwingUtilities.invokeAndWait(() -> field(fixture.window, "actionKind", JComboBox.class).setSelectedIndex(2));
            var completion = field(fixture.window, "actionCompletion", ExpressionCompletionSupport.class);
            assertNull(field(completion, "completionProvider", ExpressionCompletionSupport.CompletionProvider.class));
        }
    }

    @Test void scriptReadFailuresExposeTheirReasonAndRefreshCanRecover() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Files.delete(fixture.scriptsRoot.resolve("nested/Debug.tdscript"));
            Files.delete(fixture.scriptsRoot.resolve("nested"));
            Files.delete(fixture.scriptsRoot);
            Files.writeString(fixture.scriptsRoot, "not a directory");
            SwingUtilities.invokeAndWait(() -> {
                field(fixture.window, "actionKind", JComboBox.class).setSelectedIndex(2);
                field(fixture.window, "refreshScripts", FlatIconButton.class).doClick(0);
            });
            fixture.awaitScripts();
            SwingUtilities.invokeAndWait(() -> {
                var error = field(fixture.window, "actionError", JLabel.class);
                assertTrue(error.isVisible());
                assertTrue(error.getToolTipText().contains("Not a directory"));
                assertTrue(error.getToolTipText().contains(fixture.scriptsRoot.toString()));
            });
            Files.delete(fixture.scriptsRoot);
            Files.createDirectory(fixture.scriptsRoot);
            Files.writeString(fixture.scriptsRoot.resolve("New.tdscript"), "return 1;");
            SwingUtilities.invokeAndWait(() -> field(fixture.window, "refreshScripts", FlatIconButton.class).doClick(0));
            fixture.awaitScripts();
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(field(fixture.window, "actionError", JLabel.class).isVisible());
                assertEquals(1, field(fixture.window, "actionScript", JComboBox.class).getItemCount());
            });
        }
    }

    @Test void scriptRefreshPreservesSelectionAndMissingScriptsAreNotSilentlyRetargeted() throws Exception {
        try (var fixture = new Fixture(directory)) {
            SwingUtilities.invokeAndWait(() -> {
                field(fixture.window, "actionKind", JComboBox.class).setSelectedIndex(2);
                field(fixture.window, "actionScript", JComboBox.class).setSelectedItem("nested/Debug.tdscript");
            });
            Files.writeString(fixture.scriptsRoot.resolve("Other.tdscript"), "return 2;");
            Files.writeString(fixture.scriptsRoot.resolve("notes.txt"), "ignore");
            SwingUtilities.invokeAndWait(() -> field(fixture.window, "refreshScripts", FlatIconButton.class).doClick(0));
            fixture.awaitScripts();
            SwingUtilities.invokeAndWait(() -> {
                var choices = field(fixture.window, "actionScript", JComboBox.class);
                assertEquals(2, choices.getItemCount());
                assertEquals("nested/Debug.tdscript", choices.getSelectedItem());
            });
            Files.delete(fixture.scriptsRoot.resolve("nested/Debug.tdscript"));
            SwingUtilities.invokeAndWait(() -> field(fixture.window, "refreshScripts", FlatIconButton.class).doClick(0));
            fixture.awaitScripts();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("nested/Debug.tdscript", field(fixture.window, "actionScript", JComboBox.class).getSelectedItem());
                assertTrue(field(fixture.window, "actionError", JLabel.class).isVisible());
                assertEquals("nested/Debug.tdscript", fixture.controller.breakpoint(fixture.source.uri(), 2).request().action().script());
                field(fixture.window, "actionScript", JComboBox.class).setSelectedItem("Other.tdscript");
                assertEquals("Other.tdscript", fixture.controller.breakpoint(fixture.source.uri(), 2).request().action().script());
                assertFalse(field(fixture.window, "actionError", JLabel.class).isVisible());
            });
        }
    }

    @Test
    void invalidHitCountKeepsTheEditedRowAndText() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            SwingUtilities.invokeAndWait(() -> {
                field(fixture.window, "hitCount", JTextField.class).setText("bad");
                JList<?> list = field(fixture.window, "list", JList.class);
                list.setSelectedIndex(1);
                assertEquals(0, list.getSelectedIndex());
                assertEquals("bad", field(fixture.window, "hitCount", JTextField.class).getText());
            });
        }
    }

    @Test
    void debuggerRefreshPreservesUnfinishedEdits() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            SwingUtilities.invokeAndWait(() -> {
                field(fixture.window, "hitCount", JTextField.class).setText("bad");
                fixture.controller.setBreakpointEnabled(fixture.source.uri(), 2, false);
            });
            SwingUtilities.invokeAndWait(() ->
                    assertEquals("bad", field(fixture.window, "hitCount", JTextField.class).getText()));
        }
    }

    @Test
    void validEditsAreAppliedBeforeSwitchingRows() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            SwingUtilities.invokeAndWait(() -> {
                field(fixture.window, "hitCount", JTextField.class).setText("5");
                field(fixture.window, "list", JList.class).setSelectedIndex(1);
                assertEquals("5", fixture.controller.breakpoint(fixture.source.uri(), 2).request().hitCondition());
                assertEquals("", field(fixture.window, "hitCount", JTextField.class).getText());
            });
        }
    }

    @Test
    void escapeAndWindowCloseValidateBeforeHiding() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            SwingUtilities.invokeAndWait(() -> {
                UiTestScope.show(fixture.window);
                JTextField hitCount = field(fixture.window, "hitCount", JTextField.class);
                hitCount.setText("bad");
                fixture.window.dispatchEvent(new WindowEvent(fixture.window, WindowEvent.WINDOW_CLOSING));
                assertTrue(fixture.window.isVisible());
                invoke(fixture.window.getRootPane(), JComponent.WHEN_IN_FOCUSED_WINDOW, "ESCAPE");
                assertTrue(fixture.window.isVisible());
                hitCount.setText("7");
                invoke(fixture.window.getRootPane(), JComponent.WHEN_IN_FOCUSED_WINDOW, "ESCAPE");
                assertFalse(fixture.window.isVisible());
                assertEquals("7", fixture.controller.breakpoint(fixture.source.uri(), 2).request().hitCondition());
            });
        }
    }

    @Test
    void keyboardAndMenuShareActionsForTheSelectedBreakpoint() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            SwingUtilities.invokeAndWait(() -> {
                JList<?> list = field(fixture.window, "list", JList.class);
                list.setSelectedIndex(1);
                JPopupMenu menu = fixture.window.createContextMenu();
                assertSame(list.getActionMap().get(list.getInputMap().get(KeyStroke.getKeyStroke("ENTER"))),
                        ((JMenuItem) menu.getComponent(0)).getAction());
                assertSame(list.getActionMap().get(list.getInputMap().get(KeyStroke.getKeyStroke("DELETE"))),
                        ((JMenuItem) menu.getComponent(4)).getAction());
                invoke(list, JComponent.WHEN_FOCUSED, "ENTER");
                assertEquals(List.of(new NavigationTarget.RuntimeLine("example.Example", 3)), fixture.navigation);
                invoke(list, JComponent.WHEN_FOCUSED, "SPACE");
                assertEquals(DebuggerSessionController.BreakpointState.DISABLED,
                        fixture.controller.breakpoint(fixture.source.uri(), 3).state());
            });
            SwingUtilities.invokeAndWait(() -> {
                JList<?> list = field(fixture.window, "list", JList.class);
                assertEquals("Enable", ((JMenuItem) fixture.window.createContextMenu().getComponent(1)).getText());
                invoke(list, JComponent.WHEN_FOCUSED, "DELETE");
                assertNull(fixture.controller.breakpoint(fixture.source.uri(), 3));
                assertNotNull(fixture.controller.breakpoint(fixture.source.uri(), 2));
            });
        }
    }

    @Test
    void actionFieldsFollowTheSelectedKindAndRejectEmptyActions() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            SwingUtilities.invokeAndWait(() -> {
                JComboBox<?> kind = field(fixture.window, "actionKind", JComboBox.class);
                JPanel sourceField = field(fixture.window, "sourceField", JPanel.class);
                JavaExpressionField source = field(fixture.window, "actionSource", JavaExpressionField.class);
                JList<?> list = field(fixture.window, "list", JList.class);
                assertFalse(sourceField.isVisible());
                kind.setSelectedIndex(1);
                assertTrue(sourceField.isVisible());
                assertTrue(source.isMultiline());
                list.setSelectedIndex(1);
                assertEquals(0, list.getSelectedIndex());
                assertTrue(field(fixture.window, "actionError", JLabel.class).isVisible());
                source.setText("return 1;");
                source.postActionEvent();
                assertEquals("return 1;", fixture.controller.breakpoint(fixture.source.uri(), 2).request().action().source());
                kind.setSelectedIndex(0);
                assertFalse(sourceField.isVisible());
                assertNull(fixture.controller.breakpoint(fixture.source.uri(), 2).request().action());
                source.setText("");
                kind.setSelectedIndex(2);
                assertFalse(source.isMultiline());
                assertEquals("Script:", field(fixture.window, "sourceLabel", JLabel.class).getText());
                var scripts = field(fixture.window, "actionScript", JComboBox.class);
                scripts.setSelectedItem("nested/Debug.tdscript");
                assertEquals("nested/Debug.tdscript", fixture.controller.breakpoint(fixture.source.uri(), 2).request().action().script());
            });
        }
    }

    private static void invoke(JComponent component, int condition, String shortcut) {
        Object key = component.getInputMap(condition).get(KeyStroke.getKeyStroke(shortcut));
        Action action = component.getActionMap().get(key);
        assertNotNull(action, shortcut);
        action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, shortcut));
    }

    @Test
    void typingASpaceInSpeedSearchDoesNotToggleTheBreakpoint() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            SwingUtilities.invokeAndWait(() -> {
                JList<?> list = field(fixture.window, "list", JList.class);
                var keyboard = KeyboardFocusManager.getCurrentKeyboardFocusManager();
                keyboard.redispatchEvent(list, new KeyEvent(list, KeyEvent.KEY_TYPED,
                        System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, 'E'));
                keyboard.redispatchEvent(list, new KeyEvent(list, KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(), 0, KeyEvent.VK_SPACE, ' '));
                keyboard.redispatchEvent(list, new KeyEvent(list, KeyEvent.KEY_TYPED,
                        System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ' '));
                assertNotEquals(DebuggerSessionController.BreakpointState.DISABLED,
                        fixture.controller.breakpoint(fixture.source.uri(), 2).state());
            });
        }
    }

    @Test
    void rightClickCannotOpenAnotherRowsMenuWhenAnEditIsInvalid() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            SwingUtilities.invokeAndWait(() -> {
                UiTestScope.show(fixture.window);
                field(fixture.window, "hitCount", JTextField.class).setText("bad");
                JList<?> list = field(fixture.window, "list", JList.class);
                var bounds = list.getCellBounds(1, 1);
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                        0, 50, bounds.y + bounds.height / 2, 1, true, MouseEvent.BUTTON3));
                assertEquals(0, list.getSelectedIndex());
                assertEquals("bad", field(fixture.window, "hitCount", JTextField.class).getText());
                assertEquals(0, MenuSelectionManager.defaultManager().getSelectedPath().length);
            });
        }
    }

    @Test
    void rightClickTargetsTheClickedRowAndBlankSpaceHasNoMenu() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            SwingUtilities.invokeAndWait(() -> {
                UiTestScope.show(fixture.window);
                JList<?> list = field(fixture.window, "list", JList.class);
                var bounds = list.getCellBounds(1, 1);
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                        0, 50, bounds.y + bounds.height / 2, 1, true, MouseEvent.BUTTON3));
                assertEquals(1, list.getSelectedIndex());
                var menu = (JPopupMenu) MenuSelectionManager.defaultManager().getSelectedPath()[0];
                ((JMenuItem) menu.getComponent(0)).doClick();
                assertEquals(List.of(new NavigationTarget.RuntimeLine("example.Example", 3)), fixture.navigation);
                MenuSelectionManager.defaultManager().clearSelectedPath();
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                        0, 50, list.getHeight() - 5, 1, true, MouseEvent.BUTTON3));
                assertEquals(0, MenuSelectionManager.defaultManager().getSelectedPath().length);
            });
        }
    }

    private static <T> T field(Object instance, String name, Class<T> type) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(instance));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
        final DebugEngine.Source source = new DebugEngine.Source(
                URI.create("file:///preview/Example.java"), "example.Example", "class Example {}\n\n\n");
        BreakpointsWindow window;
        final List<NavigationTarget> navigation = new ArrayList<>();

        final Path scriptsRoot;
        Fixture(Path directory) throws Exception {
            scriptsRoot = Files.createTempDirectory(directory, "scripts");
            Files.writeString(Files.createDirectories(scriptsRoot.resolve("nested")).resolve("Debug.tdscript"), "return 1;");
            controller.toggleBreakpoint(source, 2).join();
            controller.toggleBreakpoint(source, 3).join();
            SwingUtilities.invokeAndWait(() -> window = new BreakpointsWindow(new ASTCache(), null, controller, new ScriptFiles(scriptsRoot), navigation::add));
            awaitScripts();
        }
        void awaitScripts() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                var check = new FutureTask<>(() -> field(window, "scriptsLoading", Boolean.class));
                SwingUtilities.invokeAndWait(check);
                if (!check.get()) break;
                if (System.nanoTime() > deadline) throw new AssertionError("Scripts did not load");
                Thread.sleep(10);
            }
        }

        @Override
        public void close() throws Exception {
            SwingUtilities.invokeAndWait(window::dispose);
            controller.close();
        }
    }
}
