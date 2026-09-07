package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.UiDevHarness;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class SearchEverywherePopupProcessTest {
    @Test
    void firstConstructionDoesNotDeadlockAwtClassInitialization() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());

        ProcessResult result = runProcess(Duration.ofSeconds(5), Probe.class.getName());
        assertTrue(result.finished(), "Popup initialization did not finish within five seconds\n" + result.output());
        assertEquals(0, result.exitCode(), result.output());
    }

    @Test
    void typingKeepsResultsVisibleAndTheHeaderDragsTheWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());

        ProcessResult result = runProcess(
                Duration.ofSeconds(15),
                UiDevHarness.class.getName(),
                "islands-dark",
                "--verify-search-everywhere-interactions"
        );
        assertTrue(result.finished(), "Search interaction verification timed out\n" + result.output());
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Search Everywhere interaction verification passed"), result.output());
    }

    @Test
    void tabCyclesSearchCategoriesInBothDirections() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());

        ProcessResult result = runProcess(
                Duration.ofSeconds(5),
                Probe.class.getName(),
                "verify-category-cycling"
        );
        assertTrue(result.finished(), "Category cycling verification timed out\n" + result.output());
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Search category cycling verification passed"), result.output());
    }

    private static ProcessResult runProcess(Duration timeout, String mainClass, String... arguments) throws Exception {

        String javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"
        ).toString();
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(javaExecutable);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor();
        }
        String output = new String(process.getInputStream().readAllBytes());
        return new ProcessResult(finished, process.exitValue(), output);
    }

    private record ProcessResult(boolean finished, int exitCode, String output) {
    }

    public static final class Probe {
        private Probe() {
        }

        public static void main(String[] arguments) throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                SearchEverywherePopup popup = new SearchEverywherePopup();
                try {
                    if (java.util.Arrays.asList(arguments).contains("verify-category-cycling")) {
                        verifyCategoryCycling(popup);
                        System.out.println("Search category cycling verification passed");
                    }
                } finally {
                    popup.dispose();
                }
            });
            System.exit(0);
        }

        private static void verifyCategoryCycling(SearchEverywherePopup popup) {
            JComponent query = requireNamedComponent(popup, "searchEverywhere.query", JComponent.class);
            if (query.getFocusTraversalKeysEnabled()) {
                throw new IllegalStateException("Search query still uses Tab for focus traversal");
            }

            assertSelected(popup, "all");
            invokeBinding(query, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0));
            assertSelected(popup, "classes");
            invokeBinding(query, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0));
            assertSelected(popup, "symbols");
            invokeBinding(query, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK));
            assertSelected(popup, "classes");
            invokeBinding(query, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK));
            assertSelected(popup, "all");
            invokeBinding(query, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK));
            assertSelected(popup, "text");
        }

        private static void invokeBinding(JComponent component, KeyStroke keyStroke) {
            Object actionKey = component.getInputMap(JComponent.WHEN_FOCUSED).get(keyStroke);
            Action action = actionKey == null ? null : component.getActionMap().get(actionKey);
            if (action == null) {
                throw new IllegalStateException("Missing Search Everywhere binding for " + keyStroke);
            }
            action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, actionKey.toString()));
        }

        private static void assertSelected(SearchEverywherePopup popup, String category) {
            AbstractButton button = requireNamedComponent(
                    popup,
                    "searchEverywhere.category." + category,
                    AbstractButton.class
            );
            if (!button.isSelected()) {
                throw new IllegalStateException("Search category is not selected: " + category);
            }
        }

        private static <T extends Component> T requireNamedComponent(
                Container root,
                String name,
                Class<T> type
        ) {
            T component = findNamedComponent(root, name, type);
            if (component == null) {
                throw new IllegalStateException("Component was not found: " + name);
            }
            return component;
        }

        private static <T extends Component> T findNamedComponent(Container root, String name, Class<T> type) {
            for (Component component : root.getComponents()) {
                if (name.equals(component.getName()) && type.isInstance(component)) {
                    return type.cast(component);
                }
                if (component instanceof Container child) {
                    T match = findNamedComponent(child, name, type);
                    if (match != null) {
                        return match;
                    }
                }
            }
            return null;
        }
    }
}
