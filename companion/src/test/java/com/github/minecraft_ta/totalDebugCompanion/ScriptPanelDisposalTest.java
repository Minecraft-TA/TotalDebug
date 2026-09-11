package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ScriptPanel;
import com.github.tth05.scnet.Server;
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

    @Test
    void panelsOwnTheirPopupsAndUnsubscribeOnDirectDisposal() throws Exception {
        String classpath = System.getProperty("totaldebug.testClasspath", System.getProperty("java.class.path"));
        Path log = directory.resolve("probe.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=false", "-cp", classpath, getClass().getName(), directory.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), () -> read(log));
            assertEquals(0, process.exitValue(), () -> read(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static void main(String[] args) {
        try {
            Path root = Path.of(args[0]);
            Path appHome = Files.createDirectories(root.resolve("app"));
            var launch = CompanionApp.class.getDeclaredField("launchConfiguration");
            launch.setAccessible(true);
            launch.set(null, new CompanionLaunchConfiguration(appHome));
            GlobalConfig.getInstance().loadFrom(appHome);
            CompanionApp.configureWithoutSession(CompanionProfile.forGame(Files.createDirectories(root.resolve("game"))));
            CompanionApp.configureLookAndFeel();
            CompanionApp.configureTokenMakers();
            try (var server = new Server()) {
                var bus = new TrackingBus();
                server.setMessageBus(bus);
                CompanionApp.SERVER = server;
                SwingUtilities.invokeAndWait(() -> {
                    var first = (ScriptPanel) new ScriptView("First").getComponent();
                    var second = (ScriptPanel) new ScriptView("Second").getComponent();
                    Window firstCompletion = popup(first, "codeCompletionPopup");
                    Window firstSignature = popup(first, "signatureHelpPopup");
                    Window secondCompletion = popup(second, "codeCompletionPopup");
                    assertNotSame(firstCompletion, secondCompletion);
                    firstCompletion.pack();
                    firstSignature.pack();
                    secondCompletion.pack();
                    assertEquals(Set.of(first, second), bus.owners);
                    try (var replacement = new Server()) {
                        CompanionApp.SERVER = replacement;
                        first.dispose();
                        first.dispose();
                        assertFalse(firstCompletion.isDisplayable());
                        assertFalse(firstSignature.isDisplayable());
                        assertTrue(secondCompletion.isDisplayable());
                        assertEquals(Set.of(second), bus.owners);
                        second.dispose();
                        assertTrue(bus.owners.isEmpty());
                    }
                });
            }
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
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
        @Override public <T extends AbstractMessage> void unregister(Class<T> type, Object owner) {
            super.unregister(type, owner);
            owners.remove(owner);
        }
    }
}
