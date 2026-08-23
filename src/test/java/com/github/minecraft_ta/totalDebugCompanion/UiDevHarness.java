package com.github.minecraft_ta.totalDebugCompanion;

import com.formdev.flatlaf.extras.FlatInspector;
import com.formdev.flatlaf.extras.FlatUIDefaultsInspector;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;

import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Brings the Companion UI up without a Minecraft session, so themes, icons and the settings dialog
 * can actually be looked at.
 *
 * <pre>
 * ./gradlew uiHarness
 * ./gradlew uiHarness --args=islands-light
 * ./gradlew uiHarness --args="islands-dark cycle"   # live-switches themes every 8s
 * </pre>
 *
 * <p>Lives in {@code src/test} on purpose: production static-initialisation order is delicate here
 * (see {@code SearchEverywherePopupProcessTest} and commit fbaa744), so this must not become
 * something the shipped jar can load. It deliberately calls the same
 * {@link CompanionApp#configureLookAndFeel()} the real startup path uses rather than re-implementing
 * it, so what you see is what the app does.
 *
 * <p>No session is negotiated, so capability-gated UI (Tools, Script menus) is absent, and
 * Search Everywhere needs a class index it does not have. Everything else is real.
 *
 * <p>Press F9 for FlatLaf's component inspector, F10 for the UI defaults inspector.
 */
public final class UiDevHarness {

    /** Exercises every token type the editor palette maps, so theming regressions are visible. */
    private static Path writeSampleSource(Path target) throws java.io.IOException {
        Files.writeString(target, """
                package sample;

                import java.util.List;

                /** Javadoc comment: doc colour. */
                @FunctionalInterface
                public interface ThemeSample {

                    int MAX_COUNT = 0x2A;
                    String LABEL = "string literal";

                    // line comment
                    static List<String> describe(int count, boolean verbose) {
                        double ratio = count / 3.5d;
                        char marker = 'x';
                        if (verbose && ratio > 1.0) {
                            return List.of(LABEL, String.valueOf(marker), Integer.toString(MAX_COUNT));
                        }
                        return List.of();
                    }

                    void apply(ThemeSample other);
                }
                """, java.nio.charset.StandardCharsets.UTF_8);
        return target;
    }

    /**
     * Flips between the available themes on a timer. Cold-starting each theme proves the theme files
     * are right; only switching at runtime proves everything that caches a colour, font or icon
     * actually refreshes.
     */
    private static void startThemeCycling() {
        javax.swing.Timer timer = new javax.swing.Timer(8000, null);
        timer.addActionListener(event -> {
            var themes = com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme.available();
            var current = com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager.current();
            var next = themes.get((themes.indexOf(current) + 1) % themes.size());
            System.out.println("switching theme -> " + next.id());
            com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager.apply(next);
        });
        timer.start();
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("companion-ui-harness");
        Files.createDirectories(root.resolve("scripts"));
        Files.createDirectories(root.resolve("decompiled-files"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Files.createDirectories(workspace.resolve("mods"));
        Path indexFile = Files.createFile(root.resolve("classes.jindex"));
        Path runtimeSources = Files.writeString(
                root.resolve("runtime-sources.txt"),
                "totaldebug-runtime-sources-v1\n" + workspace.toUri().toASCIIString() + "\n"
        );
        Path sample = writeSampleSource(root.resolve("decompiled-files").resolve("ThemeSample.java"));

        // Literal argument names rather than the package-private CompanionLaunchContract constants;
        // CompanionLaunchContractTest is what stops these drifting.
        CompanionLaunchConfiguration configuration = CompanionLaunchConfiguration.parse(
                new String[]{
                        "--data-directory", root.toString(),
                        "--index-file", indexFile.toString(),
                        "--workspace-directory", workspace.toString(),
                        "--session-descriptor", root.resolve("session.properties").toString()
                },
                Map.of("TOTALDEBUG_SESSION_TOKEN", "harness-token-that-is-long-enough-0123456789")
        );
        CompanionApp.configureWithoutSession(configuration);

        GlobalConfig.getInstance().loadFrom(root);
        if (args.length > 0) {
            GlobalConfig.getInstance().setThemeId(args[0]);
        }
        CompanionApp.configureLookAndFeel();

        System.out.println("UI dev harness data directory: " + root);
        System.out.println("theme: " + com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager.current().id());
        System.out.println("F9 = component inspector, F10 = UI defaults inspector");

        SwingUtilities.invokeAndWait(() -> {
            FlatInspector.install("F9");
            FlatUIDefaultsInspector.install("F10");
            MainWindow.INSTANCE.getEditorTabs().openEditorTab(new CodeView(sample, 0));
            if (args.length > 1 && "cycle".equals(args[1])) {
                startThemeCycling();
            }
            MainWindow.INSTANCE.setSize(1280, 720);
            MainWindow.INSTANCE.setVisible(true);
            UIUtils.centerJFrame(MainWindow.INSTANCE);
            ToolTipManager.sharedInstance().setInitialDelay(200);
        });
    }
}
