package com.github.minecraft_ta.totalDebugCompanion.ui.theme;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.IntelliJTheme;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Owns the active {@link CompanionTheme} and the switch between themes.
 *
 * <p>{@link FlatLaf#updateUI()} restyles the live component tree, but a fair amount of this app's
 * colour state is captured once and never re-read: {@code static final} icon constants, singleton
 * windows built in static initialisers, popups that are not always reachable from
 * {@code Window.getWindows()}, and the editor's {@code SyntaxScheme}. Those register a listener here
 * and refresh themselves explicitly.
 */
public final class ThemeManager {

    private static final List<Consumer<CompanionTheme>> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile CompanionTheme current = CompanionTheme.DEFAULT;

    private ThemeManager() {
    }

    public static CompanionTheme current() {
        return current;
    }

    public static EditorPalette palette() {
        return current.editor();
    }

    /**
     * Listeners are invoked on the EDT after the new look and feel is in place. Anything holding a
     * colour, font or icon it resolved itself belongs here.
     */
    public static void addThemeChangeListener(Consumer<CompanionTheme> listener) {
        LISTENERS.add(listener);
    }

    public static void removeThemeChangeListener(Consumer<CompanionTheme> listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Installs the theme persisted in {@link GlobalConfig}. Called during startup before any UI
     * exists, so it neither animates nor notifies listeners.
     */
    public static void installInitialTheme() {
        installTheme(CompanionTheme.byId(GlobalConfig.getInstance().themeId()));
    }

    /**
     * Installs {@code theme} as the current look and feel without animating, notifying listeners or
     * touching the live component tree. Use {@link #apply(CompanionTheme)} to switch themes at
     * runtime; this is startup (and test) plumbing.
     */
    public static void installTheme(CompanionTheme theme) {
        current = theme;
        installLookAndFeel(theme);
    }

    /**
     * Switches themes and refreshes everything that {@link FlatLaf#updateUI()} cannot reach. Must be
     * called on the EDT.
     */
    public static void apply(CompanionTheme theme) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Theme changes must be applied on the Swing event thread");
        }
        if (theme.equals(current)) {
            return;
        }
        reinstall(theme);
        GlobalConfig.getInstance().setThemeId(theme.id());
    }

    /**
     * Rebuilds the current theme in place. Used when something outside the theme itself changes the
     * UI defaults - the UI font size, for instance, which is applied during LaF install.
     */
    public static void reapply() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Theme changes must be applied on the Swing event thread");
        }
        reinstall(current);
    }

    private static void reinstall(CompanionTheme theme) {
        FlatAnimatedLafChange.showSnapshot();
        try {
            installTheme(theme);
            FlatLaf.updateUI();
            for (Consumer<CompanionTheme> listener : LISTENERS) {
                listener.accept(theme);
            }
        } finally {
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
        }
    }

    private static void installLookAndFeel(CompanionTheme theme) {
        try (InputStream stream = ThemeManager.class.getResourceAsStream(theme.resourcePath())) {
            if (stream == null) {
                throw new IOException("Missing theme resource " + theme.resourcePath());
            }
            FlatLaf.setup(IntelliJTheme.createLaf(stream));
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to install theme " + theme.id(), exception);
        }
    }
}
