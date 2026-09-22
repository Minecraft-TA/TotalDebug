package com.github.minecraft_ta.totalDebugCompanion.testui;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JDialog;
import javax.swing.PopupFactory;
import javax.swing.UIManager;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope.onEdt;
import static org.junit.jupiter.api.Assertions.*;

class UiTestSelectionTest {
    @Test void aManuallyOpenedScopeRestoresThemeAndActualLookAndFeelIndependently() throws Exception {
        var originalTheme = onEdt(ThemeManager::current);
        var originalLookAndFeel = onEdt(UIManager::getLookAndFeel);
        var originalPopups = onEdt(PopupFactory::getSharedInstance);
        var originalPopupUi = onEdt(() -> UIManager.get("PopupMenuUI"));
        var metal = new MetalLookAndFeel();
        try {
            onEdt(() -> { UIManager.setLookAndFeel(metal); return null; });
            try (var ignored = UiTestScope.open()) {
                onEdt(() -> ThemeManager.installTheme(originalTheme == CompanionTheme.ISLANDS_DARK
                        ? CompanionTheme.ISLANDS_LIGHT : CompanionTheme.ISLANDS_DARK));
            }
            onEdt(() -> {
                assertSame(originalTheme, ThemeManager.current());
                assertSame(metal, UIManager.getLookAndFeel(), "Theme metadata and installed look and feel are separate state");
            });
        } finally {
            onEdt(() -> {
                ThemeManager.installTheme(originalTheme);
                UIManager.setLookAndFeel(originalLookAndFeel);
                PopupFactory.setSharedInstance(originalPopups);
                UIManager.put("PopupMenuUI", originalPopupUi);
                return null;
            });
        }
    }

    @Test void aScopeRestoresWindowDecorationDefaults() throws Exception {
        boolean frames = onEdt(JFrame::isDefaultLookAndFeelDecorated);
        boolean dialogs = onEdt(JDialog::isDefaultLookAndFeelDecorated);
        try {
            try (var ignored = UiTestScope.open()) {
                onEdt(() -> {
                    JFrame.setDefaultLookAndFeelDecorated(!frames);
                    JDialog.setDefaultLookAndFeelDecorated(!dialogs);
                });
            }
            onEdt(() -> {
                assertEquals(frames, JFrame.isDefaultLookAndFeelDecorated());
                assertEquals(dialogs, JDialog.isDefaultLookAndFeelDecorated());
            });
        } finally {
            onEdt(() -> {
                JFrame.setDefaultLookAndFeelDecorated(frames);
                JDialog.setDefaultLookAndFeelDecorated(dialogs);
            });
        }
    }

    @Test void unmarkedWindowsAreRejectedBeforeShowingEvenIfTheTestCatchesTheError() throws Exception {
        var window = new AtomicReference<JFrame>();
        try {
            onEdt(() -> {
                var frame = new JFrame();
                window.set(frame);
                // Keep even a broken guard probe off the user's desktop.
                frame.setLocation(UiTestScope.offscreenOrigin());
                frame.setSize(100, 100);
                frame.setAutoRequestFocus(false);
                frame.setFocusableWindowState(false);
                var failure = assertThrows(AssertionError.class, () -> frame.setVisible(true));
                assertTrue(failure.getMessage().contains("without a UI scope"));
                assertFalse(frame.isVisible());
            });
            var failure = assertThrows(AssertionError.class, UiTestScope::verifyUnscopedWindows);
            assertTrue(failure.getMessage().contains("Window creation outside @UiTest"));
            onEdt(() -> assertFalse(window.get().isDisplayable(), "The rejected native peer must be disposed"));
        } finally {
            onEdt(() -> { if (window.get() != null) window.get().dispose(); });
        }
    }

    @Test void unmarkedTestsDoNotReceiveSwingSetup() throws Exception {
        onEdt(() -> {
            var frame = new JFrame();
            try {
                var failure = assertThrows(IllegalStateException.class, () -> UiTestScope.prepare(frame));
                assertTrue(failure.getMessage().contains("Open a UI test scope"));
                assertFalse(frame.isDisplayable());
            } finally { frame.dispose(); }
        });
    }

    @UiTest
    @Test void aMethodCanRequestIsolationWithoutMarkingTheEntireClass() throws Exception {
        onEdt(() -> {
            var frame = new JFrame();
            frame.setSize(200, 100);
            try {
                UiTestScope.show(frame);
                assertTrue(frame.isShowing());
                UiTestScope.assertOffscreen(frame);
            } finally { frame.dispose(); }
        });
    }
}
