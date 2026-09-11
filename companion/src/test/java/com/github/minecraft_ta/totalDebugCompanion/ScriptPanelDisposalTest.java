package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ScriptPanel;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ScriptPanelDisposalTest {
    @TempDir Path directory;

    @Test void panelsOwnTheirPopupsAndUnsubscribeOnDirectDisposal() throws Exception {
        Path home = Files.createDirectories(directory.resolve("app"));
        GlobalConfig.getInstance().loadFrom(home);
        CompanionApp.configureLookAndFeel();
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(Files.createDirectories(directory.resolve("game")))).get(10, TimeUnit.SECONDS);
            var bus = new TrackingBus();
            app.session().server().setMessageBus(bus);
            SwingUtilities.invokeAndWait(() -> {
                MainWindow window = app.createWindow();
                var first = (ScriptPanel) new ScriptView(window.editorContext(), "First").getComponent();
                var second = (ScriptPanel) new ScriptView(window.editorContext(), "Second").getComponent();
                Window firstCompletion = popup(first, "codeCompletionPopup");
                Window firstSignature = popup(first, "signatureHelpPopup");
                Window secondCompletion = popup(second, "codeCompletionPopup");
                assertNotSame(firstCompletion, secondCompletion);
                firstCompletion.pack();
                firstSignature.pack();
                secondCompletion.pack();
                assertEquals(2, bus.owners.size());
                first.dispose();
                first.dispose();
                assertFalse(firstCompletion.isDisplayable());
                assertFalse(firstSignature.isDisplayable());
                assertTrue(secondCompletion.isDisplayable());
                assertEquals(1, bus.owners.size());
                second.dispose();
                assertTrue(bus.owners.isEmpty());
            });
        }
    }

    private static Window popup(ScriptPanel panel, String name) {
        try {
            var field = ScriptPanel.class.getDeclaredField(name);
            field.setAccessible(true);
            return (Window) field.get(panel);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static String read(Path file) {
        try { return Files.readString(file); }
        catch (Exception failure) { return failure.toString(); }
    }

    private static final class TrackingBus extends DefaultMessageBus {
        private final Set<Object> owners = new HashSet<>();
        @Override public <T extends AbstractMessage> void listenAlways(Class<T> type, Object owner, Consumer<T> listener) {
            super.listenAlways(type, owner, listener);
            owners.add(owner);
        }
        @Override public <T extends AbstractMessage> void unregister(Class<T> type, Consumer<T> listener) {
            super.unregister(type, listener);
            owners.remove(listener);
        }
        @Override public <T extends AbstractMessage> void unregister(Class<T> type, Object owner) {
            super.unregister(type, owner);
            owners.remove(owner);
        }
    }
}
