package com.github.minecraft_ta.totalDebugCompanion.testui;

import com.formdev.flatlaf.FlatLaf;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;

import javax.swing.LookAndFeel;
import javax.swing.JFrame;
import javax.swing.JDialog;
import javax.swing.MenuSelectionManager;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Owns Swing test state. Component fixtures and assertions remain with their tests. */
public final class UiTestScope implements AutoCloseable {
    private static volatile UiTestScope current;
    private static WindowGuard suiteGuard;
    private final boolean ownsWindowListener;
    private final boolean desktop;
    private final Set<Window> previousWindows = new HashSet<>(Arrays.asList(Window.getWindows()));
    private final PopupFactory previousPopups = PopupFactory.getSharedInstance();
    private final KeyboardFocusManager previousFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
    private final CompanionTheme previousTheme = ThemeManager.current();
    private final LookAndFeel previousLookAndFeel = UIManager.getLookAndFeel();
    private final boolean previousDecoratedFrames = JFrame.isDefaultLookAndFeelDecorated();
    private final boolean previousDecoratedDialogs = JDialog.isDefaultLookAndFeelDecorated();
    private final Object previousPopupUi = UIManager.get("PopupMenuUI");
    private final OffscreenPopupFactory popups = new OffscreenPopupFactory();
    private final PropertyChangeListener lookAndFeelListener = event -> {
        if ("lookAndFeel".equals(event.getPropertyName())) installPopups();
    };
    private final List<String> violations = new ArrayList<>();
    private final AWTEventListener listener = this::observe;
    private TestFocusManager focus;

    private UiTestScope(boolean desktop) {
        if (current != null) throw new IllegalStateException("A UI test scope is already open");
        this.desktop = desktop;
        this.ownsWindowListener = suiteGuard == null;
        current = this;
        if (!desktop) {
            installPopups();
            UIManager.addPropertyChangeListener(lookAndFeelListener);
        }
        if (ownsWindowListener) addWindowListener(listener);
    }

    /** JUnit keeps only this guard installed between tests; unmarked tests do no Swing setup. */
    static AutoCloseable installSuiteGuard() throws Exception {
        return onEdt(() -> {
            if (suiteGuard != null) throw new IllegalStateException("The suite window guard is already installed");
            suiteGuard = new WindowGuard();
            addWindowListener(suiteGuard);
            return suiteGuard;
        });
    }

    static void verifyUnscopedWindows() throws Exception {
        if (suiteGuard != null) suiteGuard.verify();
    }

    private static void addWindowListener(AWTEventListener listener) {
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.COMPONENT_EVENT_MASK
                | AWTEvent.HIERARCHY_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK);
    }

    private void installPopups() {
        PopupFactory.setSharedInstance(popups);
        UIManager.put("PopupMenuUI", UIManager.getLookAndFeel() instanceof FlatLaf
                ? OffscreenPopupMenuUI.class.getName() : null);
    }

    public static UiTestScope open() throws Exception { return open(false); }

    static UiTestScope open(boolean desktop) throws Exception {
        return onEdt(() -> new UiTestScope(desktop));
    }

    /** Configure before showing, including windows that pack themselves in their constructor. */
    public static void prepare(Window window) {
        requireEdt();
        if (current == null) throw new IllegalStateException("Open a UI test scope before preparing a window");
        if (!current.desktop) {
            window.setAutoRequestFocus(false);
            window.setFocusableWindowState(false);
            window.setLocation(offscreenOrigin());
        }
    }

    public static void show(Window window) {
        prepare(window);
        window.setVisible(true);
    }

    /** Used by previews in both interactive and offscreen modes. */
    public static void place(Window window, Window owner, int x, int y) {
        requireEdt();
        if (current != null) prepare(window);
        window.setLocation(owner.getX() + x, owner.getY() + y);
    }

    /** Choose a point beyond every monitor, including monitors with negative desktop coordinates. */
    static Point offscreenOrigin() {
        int left = 0;
        int top = 0;
        for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle bounds = device.getDefaultConfiguration().getBounds();
            left = Math.min(left, bounds.x);
            top = Math.min(top, bounds.y);
        }
        return new Point(left - 20_000, top - 20_000);
    }

    /** Synthetic Swing focus for component behavior tests; never activates a native window. */
    public static void focus(Component component) {
        requireEdt();
        if (current == null) throw new IllegalStateException("Open a UI test scope before assigning focus");
        if (current.desktop) {
            component.requestFocusInWindow();
            return;
        }
        if (current.focus == null) {
            current.focus = new TestFocusManager();
            KeyboardFocusManager.setCurrentKeyboardFocusManager(current.focus);
        }
        Component previous = current.focus.owner;
        if (previous == component) return;
        current.focus.owner = component;
        if (previous != null) {
            var event = new FocusEvent(previous, FocusEvent.FOCUS_LOST, false, component);
            for (var listener : previous.getFocusListeners()) listener.focusLost(event);
        }
        var event = new FocusEvent(component, FocusEvent.FOCUS_GAINED, false, previous);
        for (var listener : component.getFocusListeners()) listener.focusGained(event);
    }

    public static void await(BooleanSupplier condition) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Do not wait on the EDT");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!onEdt(condition::getAsBoolean)) {
            if (System.nanoTime() >= deadline) throw new AssertionError("UI condition did not become true within 5 seconds");
            Thread.sleep(10);
        }
    }

    public static void onEdt(Runnable action) throws Exception {
        onEdt(() -> { action.run(); return null; });
    }

    public static <T> T onEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        try { return task.get(); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(failure.getCause());
        }
    }

    public static void assertOffscreen(Window window) {
        for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().intersects(window.getBounds())) {
                throw new AssertionError(window.getClass().getSimpleName() + " overlaps the desktop: " + window.getBounds());
            }
        }
        if (window.isFocusableWindow()) throw new AssertionError(window.getClass().getSimpleName() + " can take native focus");
    }

    private void observe(AWTEvent event) {
        if (desktop || !(event.getSource() instanceof Window window)) return;
        // Native peers are created by pack/show. Owned dialogs created inside production actions
        // must inherit the scope's focus policy before that peer becomes visible.
        if (event instanceof HierarchyEvent hierarchy
                && (hierarchy.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0
                && window.isDisplayable() && !window.isShowing()) {
            window.setAutoRequestFocus(false);
            window.setFocusableWindowState(false);
        }
        if (event.getID() == WindowEvent.WINDOW_GAINED_FOCUS) {
            violations.add(window.getClass().getSimpleName() + " took native focus");
        }
        boolean shown = event.getID() == ComponentEvent.COMPONENT_SHOWN;
        boolean showingChanged = event instanceof HierarchyEvent hierarchy
                && (hierarchy.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0;
        if (!shown && !(showingChanged && window.isShowing()) && !(window.isShowing()
                && (event.getID() == ComponentEvent.COMPONENT_MOVED || event.getID() == ComponentEvent.COMPONENT_RESIZED))) return;
        try { assertOffscreen(window); }
        catch (AssertionError failure) { violations.add(failure.getMessage()); }
    }

    private void verify() throws Exception {
        onEdt(() -> {
            if (!desktop) for (Window window : Window.getWindows()) {
                if (window.isShowing()) assertOffscreen(window);
            }
            if (!violations.isEmpty()) throw new AssertionError(String.join("\n", violations.stream().distinct().toList()));
        });
    }

    @Override public void close() throws Exception {
        try { verify(); }
        finally {
            onEdt(() -> {
                try {
                    MenuSelectionManager.defaultManager().clearSelectedPath();
                    for (Window window : Window.getWindows()) if (!previousWindows.contains(window)) window.dispose();
                } finally {
                    if (ownsWindowListener) Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
                    UIManager.removePropertyChangeListener(lookAndFeelListener);
                    KeyboardFocusManager.setCurrentKeyboardFocusManager(previousFocus);
                    try {
                        if (ThemeManager.current() != previousTheme) ThemeManager.installTheme(previousTheme);
                        if (UIManager.getLookAndFeel() != previousLookAndFeel) {
                            try { UIManager.setLookAndFeel(previousLookAndFeel); }
                            catch (Exception failure) { throw new IllegalStateException("Cannot restore the test look and feel", failure); }
                        }
                    } finally {
                        JFrame.setDefaultLookAndFeelDecorated(previousDecoratedFrames);
                        JDialog.setDefaultLookAndFeelDecorated(previousDecoratedDialogs);
                        PopupFactory.setSharedInstance(previousPopups);
                        UIManager.put("PopupMenuUI", previousPopupUi);
                        current = null;
                    }
                }
            });
        }
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Window setup must run on the EDT");
    }

    private static final class TestFocusManager extends DefaultKeyboardFocusManager {
        private Component owner;
        @Override public Component getFocusOwner() { return owner; }
        @Override public Component getPermanentFocusOwner() { return owner; }
    }

    private static final class WindowGuard implements AWTEventListener, AutoCloseable {
        private final ConcurrentLinkedQueue<Window> unexpected = new ConcurrentLinkedQueue<>();

        @Override public void eventDispatched(AWTEvent event) {
            UiTestScope scope = current;
            if (scope != null) {
                scope.observe(event);
            } else if (event.getSource() instanceof Window window && event instanceof HierarchyEvent hierarchy
                    && (hierarchy.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0
                    && window.isDisplayable()) {
                unexpected.add(window);
                // addNotify runs before a window is shown. Reject here, rather than after a desktop flash.
                throw new AssertionError("Window created without a UI scope: " + window.getClass().getSimpleName()
                        + ". Mark the test @UiTest and create windows in the test or @BeforeEach.");
            }
        }

        private void verify() throws Exception {
            if (unexpected.isEmpty()) return;
            var windows = new ArrayList<Window>();
            for (Window window; (window = unexpected.poll()) != null;) windows.add(window);
            onEdt(() -> windows.forEach(Window::dispose));
            throw new AssertionError("Window creation outside @UiTest: "
                    + windows.stream().map(window -> window.getClass().getSimpleName()).distinct().toList());
        }

        @Override public void close() throws Exception {
            try { verify(); }
            finally {
                onEdt(() -> {
                    Toolkit.getDefaultToolkit().removeAWTEventListener(this);
                    suiteGuard = null;
                });
            }
        }
    }
}
