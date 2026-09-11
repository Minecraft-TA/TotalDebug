package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.minecraft_ta.totaldebug.storage.RuntimePhase;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.github.minecraft_ta.totalDebugCompanion.jdt.semanticHighlighting.CustomJavaTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionTimeouts;
import com.github.minecraft_ta.totalDebugCompanion.resource.FileTypeResolver;
import com.github.minecraft_ta.totalDebugCompanion.syntax.ManifestTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.syntax.TomlTokenMaker;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import javax.swing.JDialog;
import javax.swing.JFrame;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

public final class CompanionApp {
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
    private CompanionApp() { }

    public static void main(String[] args) {
        int result = 1;
        var consoleOut = System.out;
        var consoleErr = System.err;
        try {
            var configuration = CompanionLaunchConfiguration.parse(args, System.getenv());
            var paths = configuration.paths();
            Path executable = Path.of(CompanionApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            try (var executablePin = com.github.minecraft_ta.totaldebug.storage.LaunchCache.pinRunning(paths, executable)) {
                String requestedLog = System.getProperty(com.github.minecraft_ta.totaldebug.storage.DiagnosticLogs.LOG_PROPERTY);
                var reservation = requestedLog == null
                        ? com.github.minecraft_ta.totaldebug.storage.DiagnosticLogs.reserve(paths) : null;
                try (reservation;
                     var output = com.github.minecraft_ta.totaldebug.storage.DiagnosticLogs.open(paths,
                             reservation == null ? Path.of(requestedLog) : reservation.log());
                     var stdout = DiagnosticConsole.stream(output, requestedLog == null ? consoleOut : null);
                     var stderr = DiagnosticConsole.stream(output, requestedLog == null ? consoleErr : null)) {
                    if (requestedLog != null) {
                        // Release Minecraft's inherited handles before rotating the bootstrap log on Windows.
                        consoleOut.close();
                        consoleErr.close();
                    }
                    System.setOut(stdout);
                    System.setErr(stderr);
                    result = run(args, System.getenv(), CompanionTimeouts.DEFAULT);
                } finally {
                    System.setOut(consoleOut);
                    System.setErr(consoleErr);
                }
            }
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
        }
        System.exit(result);
    }

    static int run(String[] args, Map<String, String> environment, CompanionTimeouts timeouts) {
        var startup = RuntimePhase.start("companion.startup");
        CompanionLaunchConfiguration configuration = null;
        FileChannel lockChannel = null;
        FileLock instanceLock = null;
        boolean ownsInstance = false;
        try {
            Objects.requireNonNull(timeouts, "timeouts");
            configuration = CompanionLaunchConfiguration.parse(args, environment);
            Files.createDirectories(configuration.paths().run());
            AtomicFiles.cleanupAbandonedStaging(configuration.paths().run());
            lockChannel = FileChannel.open(configuration.lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try { instanceLock = lockChannel.tryLock(); }
            catch (OverlappingFileLockException ignored) { }
            if (instanceLock == null) return 0;
            ownsInstance = true;
            Files.deleteIfExists(configuration.descriptorFile());
            String token = newInstanceToken();
            writeSecret(configuration.keyFile(), token);
            GlobalConfig.getInstance().loadFrom(configuration.appHome());
            configureLookAndFeel();
            try (var application = new CompanionApplication(configuration, token)) {
                application.startUi();
                application.start();
                startup.close();
                application.awaitExit();
            }
            return 0;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            return 1;
        } finally {
            startup.close();
            if (ownsInstance) cleanupPublishedInstance(configuration);
            try { if (instanceLock != null) instanceLock.release(); }
            catch (IOException failure) { failure.printStackTrace(System.err); }
            try { if (lockChannel != null) lockChannel.close(); }
            catch (IOException failure) { failure.printStackTrace(System.err); }
        }
    }

    static void configureLookAndFeel() {
        configureFonts();
        if (FlatLaf.supportsNativeWindowDecorations()) {
            JFrame.setDefaultLookAndFeelDecorated(true);
            JDialog.setDefaultLookAndFeelDecorated(true);
        }
        ThemeManager.installInitialTheme();
        configureTokenMakers();
    }

    static void configureTokenMakers() {
        TokenMakerFactory factory = TokenMakerFactory.getDefaultInstance();
        if (!(factory instanceof AbstractTokenMakerFactory mappings)) {
            throw new IllegalStateException("RSyntaxTextArea token factory does not support custom mappings");
        }
        mappings.putMapping(RSyntaxTextArea.SYNTAX_STYLE_JAVA, CustomJavaTokenMaker.class.getName());
        mappings.putMapping(FileTypeResolver.SYNTAX_STYLE_TOML, TomlTokenMaker.class.getName());
        mappings.putMapping(FileTypeResolver.SYNTAX_STYLE_MANIFEST, ManifestTokenMaker.class.getName());
    }

    private static void configureFonts() {
        FlatInterFont.installLazy();
        FlatJetBrainsMonoFont.installLazy();
        FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY);
        FlatLaf.setPreferredLightFontFamily(FlatInterFont.FAMILY_LIGHT);
        FlatLaf.setPreferredSemiboldFontFamily(FlatInterFont.FAMILY_SEMIBOLD);
        FlatLaf.setPreferredMonospacedFontFamily(FlatJetBrainsMonoFont.FAMILY);
    }

    private static String newInstanceToken() {
        byte[] token = new byte[32];
        TOKEN_RANDOM.nextBytes(token);
        return HexFormat.of().formatHex(token);
    }

    private static void writeSecret(Path keyFile, String token) throws IOException {
        AtomicFiles.writeSecret(keyFile, token);
    }

    private static void cleanupPublishedInstance(CompanionLaunchConfiguration current) {
        if (current == null) {
            return;
        }
        try {
            Files.deleteIfExists(current.descriptorFile());
            Files.deleteIfExists(current.keyFile());
        } catch (IOException exception) {
            exception.printStackTrace(System.err);
        }
    }
}
