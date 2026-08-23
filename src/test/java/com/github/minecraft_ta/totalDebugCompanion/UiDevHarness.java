package com.github.minecraft_ta.totalDebugCompanion;

import com.formdev.flatlaf.extras.FlatInspector;
import com.formdev.flatlaf.extras.FlatUIDefaultsInspector;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.LazyFileJTree;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.tth05.jindex.ClassIndex;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    /** Populates the Files tree with the archive entry types used by the icon and spacing pass. */
    private static void writeSampleArchive(Path target) throws java.io.IOException {
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(target))) {
            addArchiveEntry(archive, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
            addArchiveEntry(archive, "assets/sample/textures/gui/debug.png", "sample-image");
            addArchiveEntry(archive, "assets/sample/lang/en_us.json", "{}\n");
            addArchiveEntry(archive, "assets/sample/font/ui.ttf", "sample-font");
            addArchiveEntry(archive, "data/sample/tags/blocks/debug.json", "{}\n");
            addArchiveEntry(archive, "sample/api/Example.class", "sample-class");
            addArchiveEntry(archive, "sample/api/package-info.class", "sample-package");
            addArchiveEntry(archive, "config/defaults.toml", "enabled=true\n");
            addArchiveEntry(archive, "docs/README.md", "# Sample\n");
            addArchiveEntry(archive, "pack.mcmeta", "{}\n");
        }
    }

    private static void addArchiveEntry(ZipOutputStream archive, String name, String contents)
            throws java.io.IOException {
        archive.putNextEntry(new ZipEntry(name));
        archive.write(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        archive.closeEntry();
    }

    private static void writeSampleClassIndex(Path target) throws java.io.IOException {
        try (ClassIndex index = ClassIndex.fromBytes(List.of(
                classBytes(Object.class),
                classBytes(String.class),
                classBytes(List.class),
                classBytes(FunctionalInterface.class)
        ))) {
            index.saveToFile(target.toString());
        }
    }

    private static byte[] classBytes(Class<?> type) throws java.io.IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = Objects.requireNonNull(type.getResourceAsStream(resource), resource)) {
            return stream.readAllBytes();
        }
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

    private static void expandTreeForScreenshot() {
        LazyFileJTree tree = findComponent(MainWindow.INSTANCE, LazyFileJTree.class);
        if (tree == null) {
            return;
        }
        javax.swing.Timer timer = new javax.swing.Timer(120, null);
        timer.addActionListener(event -> {
            for (int row = 0; row < tree.getRowCount(); row++) {
                tree.expandRow(row);
            }
        });
        timer.setRepeats(true);
        timer.start();
        javax.swing.Timer stopTimer = new javax.swing.Timer(1000, event -> timer.stop());
        stopTimer.setRepeats(false);
        stopTimer.start();
    }

    private static void scheduleScreenshot(Path target) {
        javax.swing.Timer timer = new javax.swing.Timer(1400, event -> {
            try {
                Files.createDirectories(target.toAbsolutePath().getParent());
                BufferedImage image = new BufferedImage(
                        MainWindow.INSTANCE.getWidth(),
                        MainWindow.INSTANCE.getHeight(),
                        BufferedImage.TYPE_INT_ARGB
                );
                Graphics2D graphics = image.createGraphics();
                MainWindow.INSTANCE.paintAll(graphics);
                graphics.dispose();
                ImageIO.write(image, "png", target.toFile());
                System.out.println("UI screenshot: " + target.toAbsolutePath());
            } catch (Exception exception) {
                exception.printStackTrace(System.err);
                System.exit(1);
            }
            MainWindow.INSTANCE.dispose();
            System.exit(0);
        });
        timer.setRepeats(false);
        timer.start();
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
        Path mods = Files.createDirectories(workspace.resolve("mods"));
        writeSampleArchive(mods.resolve("companion-ui-sample.jar"));
        Path indexFile = root.resolve("classes.jindex");
        writeSampleClassIndex(indexFile);
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
        CompanionClassIndex.open(indexFile);

        GlobalConfig.getInstance().loadFrom(root);
        var positionalArguments = Arrays.stream(args).filter(argument -> !argument.startsWith("--")).toList();
        if (!positionalArguments.isEmpty()) {
            GlobalConfig.getInstance().setThemeId(positionalArguments.getFirst());
        }
        Path screenshot = Arrays.stream(args)
                .filter(argument -> argument.startsWith("--screenshot="))
                .map(argument -> Path.of(argument.substring("--screenshot=".length())))
                .findFirst()
                .orElse(null);
        CompanionApp.configureLookAndFeel();

        System.out.println("UI dev harness data directory: " + root);
        System.out.println("theme: " + com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager.current().id());
        System.out.println("F9 = component inspector, F10 = UI defaults inspector");

        SwingUtilities.invokeAndWait(() -> {
            FlatInspector.install("F9");
            FlatUIDefaultsInspector.install("F10");
            MainWindow.INSTANCE.getEditorTabs().openEditorTab(new CodeView(sample, 0));
            if (positionalArguments.size() > 1 && "cycle".equals(positionalArguments.get(1))) {
                startThemeCycling();
            }
            MainWindow.INSTANCE.setSize(1280, 720);
            MainWindow.INSTANCE.setVisible(true);
            UIUtils.centerJFrame(MainWindow.INSTANCE);
            ToolTipManager.sharedInstance().setInitialDelay(200);
            if (screenshot != null) {
                expandTreeForScreenshot();
                scheduleScreenshot(screenshot);
            }
        });
    }
}
