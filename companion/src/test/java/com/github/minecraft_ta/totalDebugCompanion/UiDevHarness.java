package com.github.minecraft_ta.totalDebugCompanion;

import com.formdev.flatlaf.extras.FlatInspector;
import com.formdev.flatlaf.extras.FlatUIDefaultsInspector;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.decompile.DecompiledSource;
import com.github.minecraft_ta.totalDebugCompanion.model.EditorLocation;
import com.github.minecraft_ta.totalDebugCompanion.model.ResourceView;
import com.github.minecraft_ta.totalDebugCompanion.model.UsagesView;
import com.github.minecraft_ta.totalDebugCompanion.resource.ArchiveEntrySource;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.HierarchyPreviewPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.ImplementationChooserPopup;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.SearchEverywherePopup;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexSource;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.JList;
import javax.swing.ToolTipManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.IconRowHeader;
import java.awt.Component;
import java.awt.Container;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;

/**
 * Brings the Companion UI up without a Minecraft session, so themes, icons and the settings dialog
 * can actually be looked at.
 *
 * <pre>
 * ./gradlew :companion:uiHarness
 * ./gradlew :companion:uiHarness --args="--theme=islands-light --scenario=search-results"
 * ./gradlew :companion:uiContactSheet
 * </pre>
 *
 * <p>Lives in {@code src/test} on purpose: production static-initialisation order is delicate here
 * (see {@code SearchEverywherePopupProcessTest} and commit fbaa744), so this must not become
 * something the shipped jar can load. It deliberately calls the same
 * {@link CompanionApp#configureLookAndFeel()} the real startup path uses rather than re-implementing
 * it, so what you see is what the app does.
 *
 * <p>The UI uses an offline sample profile, and Search Everywhere uses the generated sample class index.
 *
 * <p>Press F9 for FlatLaf's component inspector, F10 for the UI defaults inspector.
 */
public final class UiDevHarness {

    /** Exercises every token type the editor palette maps, so theming regressions are visible. */
    private static Path writeSampleSource(Path target) throws java.io.IOException {
        Files.writeString(target, """
                package sample;

                import java.util.List;
import java.util.concurrent.TimeUnit;

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

                final class ThemeSampleImpl implements ThemeSample {
                    private int applications;

                    @Override
                    public void apply(ThemeSample other) {
                        applications++;
                        ThemeSample.describe(applications, other != null);
                    }
                }

                final class ThemeSampleOther implements ThemeSample {
                    @Override
                    public void apply(ThemeSample other) {
                    }
                }

                interface SingleAction {
                    void execute();
                }

                final class SingleActionImpl implements SingleAction {
                    @Override
                    public void execute() {
                    }
                }

                class OverrideBase {
                    public void overrideMe(
                            ThemeSample first,
                            ThemeSample second,
                            ThemeSample third,
                            ThemeSample fourth,
                            ThemeSample fifth,
                            ThemeSample sixth,
                            ThemeSample seventh,
                            ThemeSample eighth
                    ) {
                    }
                }

                final class OverrideChild extends OverrideBase {
                    @Override
                    public void overrideMe(
                            ThemeSample first,
                            ThemeSample second,
                            ThemeSample third,
                            ThemeSample fourth,
                            ThemeSample fifth,
                            ThemeSample sixth,
                            ThemeSample seventh,
                            ThemeSample eighth
                    ) {
                    }
                }
                """ + "\n".repeat(20), java.nio.charset.StandardCharsets.UTF_8);
        return target;
    }

    /** Populates the Files tree with the archive entry types used by the icon and spacing pass. */
    private static void writeSampleArchive(Path target) throws java.io.IOException {
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(target))) {
            addArchiveEntry(archive, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
            addArchiveEntry(archive, "assets/sample/textures/gui/debug.png", samplePng());
            addArchiveEntry(archive, "assets/sample/lang/en_us.json", "{\"debug.sample\": \"Hello\"}\n");
            addArchiveEntry(archive, "assets/sample/font/ui.ttf", "sample-font");
            addArchiveEntry(archive, "data/sample/tags/blocks/debug.json", "{}\n");
            addArchiveEntry(archive, "sample/api/Example.class", "sample-class");
            addArchiveEntry(archive, "sample/api/package-info.class", "sample-package");
            addArchiveEntry(archive, "config/defaults.toml", "enabled = true\nmessage = \"Hello\"\n");
            addArchiveEntry(archive, "config/legacy.cfg", "[general]\nenabled = true\n");
            addArchiveEntry(archive, "docs/README.md", "# Sample\n");
            addArchiveEntry(archive, "docs/NOTICE.custom", "An unknown but valid text file.\n");
            addArchiveEntry(archive, "pack.mcmeta", "{}\n");
        }
    }

    private static void addArchiveEntry(ZipOutputStream archive, String name, String contents)
            throws java.io.IOException {
        addArchiveEntry(archive, name, contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void addArchiveEntry(ZipOutputStream archive, String name, byte[] contents)
            throws java.io.IOException {
        archive.putNextEntry(new ZipEntry(name));
        archive.write(contents);
        archive.closeEntry();
    }

    private static byte[] samplePng() throws java.io.IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(0x4878D0));
        graphics.fillRect(8, 8, 48, 48);
        graphics.setColor(new Color(0xE8B84A));
        graphics.fillRect(20, 20, 24, 24);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static void writeSampleClassIndex(Path target, Path sampleClasses) throws java.io.IOException {
        List<byte[]> classes = new java.util.ArrayList<>(List.of(
                classBytes(Object.class),
                classBytes(String.class),
                classBytes(List.class),
                classBytes(FunctionalInterface.class)
        ));
        try (var paths = Files.walk(sampleClasses)) {
            for (Path classFile : paths.filter(path -> path.toString().endsWith(".class")).toList()) {
                classes.add(Files.readAllBytes(classFile));
            }
        }
        try (ClassIndex index = ClassIndex.fromSources(
                classes.stream().map(bytes -> IndexSource.classFile(0, bytes)).toList()
        )) {
            index.saveToFile(target.toString());
        }
    }

    private static void compileSampleSource(Path source, Path classes) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("UI harness requires a full JDK");
        }
        int result = compiler.run(
                null,
                null,
                null,
                "--release",
                "21",
                "-d",
                classes.toString(),
                source.toString()
        );
        if (result != 0) {
            throw new IllegalStateException("Unable to compile the UI harness source fixture");
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

    private static void scheduleSearchEverywhereInteractionVerification() {
        javax.swing.Timer openTimer = new javax.swing.Timer(500, event -> {
            MainWindow.INSTANCE.openSearchEverywhere();
                SearchEverywherePopup popup = Arrays.stream(java.awt.Window.getWindows())
                        .filter(SearchEverywherePopup.class::isInstance)
                        .map(SearchEverywherePopup.class::cast)
                        .filter(java.awt.Window::isShowing)
                        .findFirst()
                        .orElseThrow();
            // This fixture sends synthetic events; native focus belongs to the user's other windows.
            var focusListeners = popup.getWindowFocusListeners();
            for (var listener : focusListeners) popup.removeWindowFocusListener(listener);
            javax.swing.Timer firstQuery = new javax.swing.Timer(250, queryEvent -> {
                popup.setLocation(MainWindow.INSTANCE.getX() + 220, MainWindow.INSTANCE.getY() + 70);
                FlatIconTextField search = findComponent(popup, FlatIconTextField.class);
                if (search == null) {
                    throw new IllegalStateException("Search Everywhere query field was not found");
                }
                search.setText("Theme");

                long resultsDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                javax.swing.Timer verifyResults = new javax.swing.Timer(50, verifyEvent -> {
                    @SuppressWarnings("rawtypes")
                    JList results = findComponent(popup, JList.class);
                    if (results == null || results.getModel().getSize() == 0 || !results.isShowing()) {
                        if (System.nanoTime() < resultsDeadline) return;
                        throw new IllegalStateException("Initial Search Everywhere results did not become visible");
                    }

                    ((javax.swing.Timer) verifyEvent.getSource()).stop();
                    int previousResultCount = results.getModel().getSize();
                    search.setText("ThemeSampleImpl");
                    if (!results.isShowing() || results.getModel().getSize() != previousResultCount) {
                        throw new IllegalStateException("Typing replaced visible results with a transient blank state");
                    }

                    Component dragSurface = findNamedComponent(popup, "searchEverywhere.dragSurface");
                    if (dragSurface == null) {
                        throw new IllegalStateException("Search Everywhere drag surface was not found");
                    }
                    Point before = popup.getLocation();
                    dispatchWindowDrag(dragSurface, 36, 24);
                    Point after = popup.getLocation();
                    if (!after.equals(new Point(before.x + 36, before.y + 24))) {
                        throw new IllegalStateException(
                                "Search Everywhere drag moved to " + after + " instead of the expected offset"
                        );
                    }

                    long updatedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    javax.swing.Timer verifyUpdated = new javax.swing.Timer(50, updatedEvent -> {
                        if (results.getModel().getSize() != 1 || !results.isShowing()) {
                            if (System.nanoTime() < updatedDeadline) return;
                            throw new IllegalStateException("Updated Search Everywhere results did not remain visible");
                        }
                        ((javax.swing.Timer) updatedEvent.getSource()).stop();
                        for (var listener : focusListeners) popup.addWindowFocusListener(listener);
                        var lostFocus = new WindowEvent(popup, WindowEvent.WINDOW_LOST_FOCUS);
                        for (var listener : focusListeners) listener.windowLostFocus(lostFocus);
                        if (popup.isVisible()) throw new IllegalStateException("Losing focus must dismiss Search Everywhere");
                        System.out.println("Search Everywhere interaction verification passed");
                        popup.dispose();
                        MainWindow.INSTANCE.dispose();
                        System.exit(0);
                    });
                    verifyUpdated.start();
                });
                verifyResults.start();
            });
            firstQuery.setRepeats(false);
            firstQuery.start();
        });
        openTimer.setRepeats(false);
        openTimer.start();
    }

    private static void scheduleMethodNavigationVerification() {
        javax.swing.Timer openTimer = new javax.swing.Timer(500, event -> CompanionApp.openClass(
                "sample.ThemeSampleImpl",
                org.eclipse.jdt.core.IJavaElement.METHOD,
                "Lsample/ThemeSampleImpl;.apply(Lsample/ThemeSample;)V"
        ));
        openTimer.setRepeats(false);
        openTimer.start();

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        javax.swing.Timer verifyTimer = new javax.swing.Timer(100, event -> {
            try {
                var selected = MainWindow.INSTANCE.getEditorTabs().getSelectedEditor();
                if (selected instanceof CodeView codeView
                        && codeView.getTitle().equals("ThemeSampleImpl")) {
                    RSyntaxTextArea editor = findComponent(
                            (Container) codeView.getComponent(),
                            RSyntaxTextArea.class
                    );
                    int caret = editor.getCaretPosition();
                    int line = editor.getLineOfOffset(caret);
                    int lineStart = editor.getLineStartOffset(line);
                    String lineText = editor.getText(lineStart, editor.getLineEndOffset(line) - lineStart);
                    if (lineText.contains(" apply(")) {
                        ((javax.swing.Timer) event.getSource()).stop();
                        System.out.println("METHOD_NAVIGATION_OK line=" + (line + 1) + " caret=" + caret);
                        MainWindow.INSTANCE.dispose();
                        System.exit(0);
                    }
                }
                if (System.nanoTime() >= deadline) {
                    String detail = selected == null
                            ? "selected=null"
                            : "selected=" + selected.getClass().getSimpleName() + ":" + selected.getTitle();
                    if (selected instanceof CodeView codeView) {
                        RSyntaxTextArea editor = findComponent(
                                (Container) codeView.getComponent(),
                                RSyntaxTextArea.class
                        );
                        int line = editor.getLineOfOffset(editor.getCaretPosition());
                        detail += " caret=" + editor.getCaretPosition() + " line=" + (line + 1)
                                + " text=" + editor.getText(
                                editor.getLineStartOffset(line),
                                editor.getLineEndOffset(line) - editor.getLineStartOffset(line)
                        ).strip();
                        detail += " readyDone=" + codeView.ready().isDone()
                                + " readyExceptional=" + codeView.ready().isCompletedExceptionally();
                        if (codeView.ready().isCompletedExceptionally()) {
                            try {
                                codeView.ready().join();
                            } catch (java.util.concurrent.CompletionException failure) {
                                detail += " readyFailure=" + failure.getCause();
                            }
                        }
                    }
                    throw new IllegalStateException(
                            "Method navigation did not place the caret on ThemeSampleImpl.apply; " + detail
                    );
                }
            } catch (Exception exception) {
                exception.printStackTrace(System.err);
                MainWindow.INSTANCE.dispose();
                System.exit(2);
            }
        });
        verifyTimer.setInitialDelay(0);
        verifyTimer.start();
    }

    private static Component findNamedComponent(Container root, String name) {
        if (name.equals(root.getName())) {
            return root;
        }
        for (Component component : root.getComponents()) {
            if (name.equals(component.getName())) {
                return component;
            }
            if (component instanceof Container child) {
                Component match = findNamedComponent(child, name);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static void dispatchWindowDrag(Component component, int deltaX, int deltaY) {
        Point screen = component.getLocationOnScreen();
        long now = System.currentTimeMillis();
        component.dispatchEvent(new MouseEvent(
                component,
                MouseEvent.MOUSE_PRESSED,
                now,
                MouseEvent.BUTTON1_DOWN_MASK,
                4,
                4,
                screen.x + 4,
                screen.y + 4,
                1,
                false,
                MouseEvent.BUTTON1
        ));
        component.dispatchEvent(new MouseEvent(
                component,
                MouseEvent.MOUSE_DRAGGED,
                now + 1,
                MouseEvent.BUTTON1_DOWN_MASK,
                4 + deltaX,
                4 + deltaY,
                screen.x + 4 + deltaX,
                screen.y + 4 + deltaY,
                0,
                false,
                MouseEvent.NOBUTTON
        ));
        component.dispatchEvent(new MouseEvent(
                component,
                MouseEvent.MOUSE_RELEASED,
                now + 2,
                0,
                4 + deltaX,
                4 + deltaY,
                screen.x + 4 + deltaX,
                screen.y + 4 + deltaY,
                1,
                false,
                MouseEvent.BUTTON1
        ));
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

    private static void scheduleCodeVisionClickVerification(String source) {
        javax.swing.Timer clickTimer = new javax.swing.Timer(1400, event -> {
            try {
                RSyntaxTextArea editor = findComponent(MainWindow.INSTANCE, RSyntaxTextArea.class);
                if (editor == null) {
                    throw new IllegalStateException("Code editor was not found");
                }
                int declaration = source.indexOf("public interface ThemeSample");
                int anchorOffset = source.indexOf('{', declaration) + 1;
                Rectangle2D anchor = editor.modelToView2D(anchorOffset);
                Point target = new Point(
                        (int) Math.ceil(anchor.getX()) + 22,
                        (int) Math.floor(anchor.getY() + anchor.getHeight() / 2)
                );

                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                javax.swing.Timer resultTimer = new javax.swing.Timer(250, resultEvent -> {
                    var selected = MainWindow.INSTANCE.getEditorTabs().getSelectedEditor();
                    if (selected instanceof UsagesView) {
                        ((javax.swing.Timer) resultEvent.getSource()).stop();
                        System.out.println("CODE_VISION_CLICK_OK");
                        MainWindow.INSTANCE.dispose();
                        System.exit(0);
                    }
                    if (System.nanoTime() >= deadline) {
                        ((javax.swing.Timer) resultEvent.getSource()).stop();
                        System.err.println("CODE_VISION_CLICK_MISSED selected="
                                + (selected == null ? "null" : selected.getClass().getName()));
                        MainWindow.INSTANCE.dispose();
                        System.exit(2);
                    }
                    dispatchMouseMove(editor, target);
                    dispatchLeftClick(editor, target);
                });
                resultTimer.setInitialDelay(0);
                resultTimer.start();
            } catch (Exception exception) {
                exception.printStackTrace(System.err);
                MainWindow.INSTANCE.dispose();
                System.exit(3);
            }
        });
        clickTimer.setRepeats(false);
        clickTimer.start();
    }

    private static void scheduleGutterClickVerification(String source) {
        javax.swing.Timer setupTimer = new javax.swing.Timer(1400, event -> {
            try {
                RSyntaxTextArea editor = findComponent(MainWindow.INSTANCE, RSyntaxTextArea.class);
                IconRowHeader iconRow = findComponent(MainWindow.INSTANCE, IconRowHeader.class);
                if (editor == null || iconRow == null) {
                    throw new IllegalStateException("Code editor gutter was not found");
                }
                Rectangle2D declaration = editor.modelToView2D(source.indexOf("public interface ThemeSample"));
                Point target = SwingUtilities.convertPoint(
                        editor,
                        0,
                        (int) Math.floor(declaration.getY() + declaration.getHeight() / 2),
                        iconRow
                );
                target.x = iconRow.getWidth() / 2;
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                javax.swing.Timer resultTimer = new javax.swing.Timer(250, resultEvent -> {
                    if (isShowing(ImplementationChooserPopup.class)) {
                        ((javax.swing.Timer) resultEvent.getSource()).stop();
                        System.out.println("GUTTER_CLICK_OK");
                        MainWindow.INSTANCE.dispose();
                        System.exit(0);
                    }
                    if (System.nanoTime() >= deadline) {
                        ((javax.swing.Timer) resultEvent.getSource()).stop();
                        System.err.println("GUTTER_CLICK_MISSED");
                        MainWindow.INSTANCE.dispose();
                        System.exit(2);
                    }
                    dispatchMouseMove(iconRow, target);
                    dispatchLeftClick(iconRow, target);
                });
                resultTimer.setInitialDelay(0);
                resultTimer.start();
            } catch (Exception exception) {
                exception.printStackTrace(System.err);
                MainWindow.INSTANCE.dispose();
                System.exit(3);
            }
        });
        setupTimer.setRepeats(false);
        setupTimer.start();
    }

    private static void scheduleSingleGutterNavigationVerification(String source) {
        javax.swing.Timer setupTimer = new javax.swing.Timer(1400, event -> {
            try {
                RSyntaxTextArea editor = findComponent(MainWindow.INSTANCE, RSyntaxTextArea.class);
                IconRowHeader iconRow = findComponent(MainWindow.INSTANCE, IconRowHeader.class);
                if (editor == null || iconRow == null) {
                    throw new IllegalStateException("Code editor gutter was not found");
                }
                Rectangle2D declaration = editor.modelToView2D(source.indexOf("interface SingleAction"));
                Point target = SwingUtilities.convertPoint(
                        editor,
                        0,
                        (int) Math.floor(declaration.getY() + declaration.getHeight() / 2),
                        iconRow
                );
                target.x = iconRow.getWidth() / 2;
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                javax.swing.Timer resultTimer = new javax.swing.Timer(250, resultEvent -> {
                    var selected = MainWindow.INSTANCE.getEditorTabs().getSelectedEditor();
                    if (selected instanceof CodeView codeView && "SingleActionImpl".equals(codeView.getTitle())) {
                        ((javax.swing.Timer) resultEvent.getSource()).stop();
                        System.out.println("GUTTER_DIRECT_NAVIGATION_OK");
                        MainWindow.INSTANCE.dispose();
                        System.exit(0);
                    }
                    if (System.nanoTime() >= deadline) {
                        ((javax.swing.Timer) resultEvent.getSource()).stop();
                        System.err.println("GUTTER_DIRECT_NAVIGATION_MISSED selected="
                                + (selected == null ? "null" : selected.getTitle()));
                        MainWindow.INSTANCE.dispose();
                        System.exit(2);
                    }
                    dispatchMouseMove(iconRow, target);
                    dispatchLeftClick(iconRow, target);
                });
                resultTimer.setInitialDelay(0);
                resultTimer.start();
            } catch (Exception exception) {
                exception.printStackTrace(System.err);
                MainWindow.INSTANCE.dispose();
                System.exit(3);
            }
        });
        setupTimer.setRepeats(false);
        setupTimer.start();
    }

    private static void scheduleGutterHover(String source, int declarationOffset, boolean verify) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        javax.swing.Timer hoverTimer = new javax.swing.Timer(150, event -> {
            try {
                RSyntaxTextArea editor = findComponent(MainWindow.INSTANCE, RSyntaxTextArea.class);
                IconRowHeader iconRow = findComponent(MainWindow.INSTANCE, IconRowHeader.class);
                if (editor == null || iconRow == null) {
                    return;
                }
                int line = editor.getLineOfOffset(declarationOffset);
                if (iconRow.getTrackingIcons(line).length == 0) {
                    if (System.nanoTime() >= deadline && verify) {
                        throw new IllegalStateException("Hierarchy marker was not installed");
                    }
                    return;
                }

                if (verify) {
                    ((javax.swing.Timer) event.getSource()).stop();
                }
                Rectangle2D declaration = editor.modelToView2D(declarationOffset);
                var viewport = (javax.swing.JViewport) SwingUtilities.getAncestorOfClass(
                        javax.swing.JViewport.class,
                        editor
                );
                if (viewport != null) {
                    int targetY = Math.max(0, (int) declaration.getY() - viewport.getExtentSize().height / 2);
                    viewport.setViewPosition(new Point(viewport.getViewPosition().x, targetY));
                }
                declaration = editor.modelToView2D(declarationOffset);
                Point target = SwingUtilities.convertPoint(
                        editor,
                        0,
                        (int) Math.floor(declaration.getY() + declaration.getHeight() / 2),
                        iconRow
                );
                target.x = iconRow.getWidth() / 2;
                dispatchMouseMove(iconRow, target);

                if (verify) {
                    scheduleWindowVerification(HierarchyPreviewPopup.class, "GUTTER_PREVIEW_OK");
                }
            } catch (Exception exception) {
                exception.printStackTrace(System.err);
                MainWindow.INSTANCE.dispose();
                System.exit(3);
            }
        });
        hoverTimer.setInitialDelay(1000);
        hoverTimer.start();
    }

    private static <T extends java.awt.Window> boolean isShowing(Class<T> type) {
        return Arrays.stream(java.awt.Window.getWindows()).anyMatch(window -> type.isInstance(window) && window.isShowing());
    }

    private static <T extends java.awt.Window> void scheduleWindowVerification(Class<T> type, String successMessage) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        javax.swing.Timer timer = new javax.swing.Timer(100, event -> {
            if (isShowing(type)) {
                ((javax.swing.Timer) event.getSource()).stop();
                System.out.println(successMessage);
                MainWindow.INSTANCE.dispose();
                System.exit(0);
            }
            if (System.nanoTime() >= deadline) {
                ((javax.swing.Timer) event.getSource()).stop();
                System.err.println(successMessage.replace("_OK", "_MISSED"));
                MainWindow.INSTANCE.dispose();
                System.exit(2);
            }
        });
        timer.start();
    }

    private static void scheduleHierarchyRowLayoutVerification() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        javax.swing.Timer timer = new javax.swing.Timer(100, event -> {
            HierarchyPreviewPopup popup = Arrays.stream(java.awt.Window.getWindows())
                    .filter(HierarchyPreviewPopup.class::isInstance)
                    .map(HierarchyPreviewPopup.class::cast)
                    .filter(java.awt.Window::isShowing)
                    .findFirst()
                    .orElse(null);
            javax.swing.JLabel declaration = popup == null
                    ? null
                    : findLabelContaining(popup, "OverrideChild.overrideMe");
            if (declaration != null) {
                ((javax.swing.Timer) event.getSource()).stop();
                PrimarySecondaryLabel declarationPresentation = (PrimarySecondaryLabel) declaration.getParent();
                Container row = declarationPresentation.getParent();
                PrimarySecondaryLabel modulePresentation = Arrays.stream(row.getComponents())
                        .filter(PrimarySecondaryLabel.class::isInstance)
                        .map(PrimarySecondaryLabel.class::cast)
                        .filter(presentation -> presentation != declarationPresentation)
                        .findFirst()
                        .orElseThrow();
                javax.swing.JLabel module = firstLabel(modulePresentation);
                Point declarationOrigin = SwingUtilities.convertPoint(declaration, 0, 0, row);
                Point moduleOrigin = SwingUtilities.convertPoint(module, 0, 0, row);
                int declarationBaseline = declarationOrigin.y
                        + declaration.getBaseline(declaration.getWidth(), declaration.getHeight());
                int moduleBaseline = moduleOrigin.y + module.getBaseline(module.getWidth(), module.getHeight());
                if (declarationPresentation.getPreferredSize().height > declarationPresentation.getHeight()
                        || modulePresentation.getPreferredSize().height > modulePresentation.getHeight()
                        || declarationPresentation.getY() + declarationPresentation.getHeight() > row.getHeight()
                        || modulePresentation.getY() + modulePresentation.getHeight() > row.getHeight()
                        || Math.abs(declarationBaseline - moduleBaseline) > 2) {
                    System.err.printf(
                            "HIERARCHY_ROW_LAYOUT_CLIPPED: row=%s declaration=%s preferred=%s baseline=%d "
                                    + "module=%s preferred=%s baseline=%d%n",
                            row.getSize(),
                            declarationPresentation.getSize(),
                            declarationPresentation.getPreferredSize(),
                            declarationBaseline,
                            modulePresentation.getSize(),
                            modulePresentation.getPreferredSize(),
                            moduleBaseline
                    );
                    MainWindow.INSTANCE.dispose();
                    System.exit(4);
                }
                System.out.println("HIERARCHY_ROW_LAYOUT_OK");
                MainWindow.INSTANCE.dispose();
                System.exit(0);
            }
            if (System.nanoTime() >= deadline) {
                ((javax.swing.Timer) event.getSource()).stop();
                System.err.println("HIERARCHY_ROW_LAYOUT_MISSED");
                MainWindow.INSTANCE.dispose();
                System.exit(2);
            }
        });
        timer.start();
    }

    private static javax.swing.JLabel findLabelContaining(Container root, String expectedText) {
        for (Component component : root.getComponents()) {
            if (component instanceof javax.swing.JLabel label
                    && label.getText() != null
                    && label.getText().contains(expectedText)) {
                return label;
            }
            if (component instanceof Container child) {
                javax.swing.JLabel match = findLabelContaining(child, expectedText);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static javax.swing.JLabel firstLabel(Container root) {
        return Arrays.stream(root.getComponents())
                .filter(javax.swing.JLabel.class::isInstance)
                .map(javax.swing.JLabel.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static void dispatchMouseMove(Component component, Point point) {
        component.dispatchEvent(new MouseEvent(
                component,
                MouseEvent.MOUSE_ENTERED,
                System.currentTimeMillis(),
                0,
                point.x,
                point.y,
                0,
                false,
                MouseEvent.NOBUTTON
        ));
        component.dispatchEvent(new MouseEvent(
                component,
                MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),
                0,
                point.x,
                point.y,
                0,
                false,
                MouseEvent.NOBUTTON
        ));
    }

    private static void dispatchLeftClick(Component component, Point point) {
        long now = System.currentTimeMillis();
        component.dispatchEvent(new MouseEvent(
                component,
                MouseEvent.MOUSE_PRESSED,
                now,
                MouseEvent.BUTTON1_DOWN_MASK,
                point.x,
                point.y,
                1,
                false,
                MouseEvent.BUTTON1
        ));
        component.dispatchEvent(new MouseEvent(
                component,
                MouseEvent.MOUSE_RELEASED,
                now + 1,
                0,
                point.x,
                point.y,
                1,
                false,
                MouseEvent.BUTTON1
        ));
        component.dispatchEvent(new MouseEvent(
                component,
                MouseEvent.MOUSE_CLICKED,
                now + 2,
                0,
                point.x,
                point.y,
                1,
                false,
                MouseEvent.BUTTON1
        ));
    }

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--list-scenarios")) {
            for (UiRenderScenario scenario : UiRenderScenario.values()) {
                System.out.println(scenario.id() + "\t" + scenario.description());
            }
            return;
        }
        Path root = Files.createTempDirectory("companion-ui-harness");
        Files.createDirectories(root.resolve("scripts"));
        Files.createDirectories(root.resolve("decompiled-files"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path mods = Files.createDirectories(workspace.resolve("mods"));
        Path sampleArchive = mods.resolve("companion-ui-sample.jar");
        writeSampleArchive(sampleArchive);
        Path sample = writeSampleSource(root.resolve("decompiled-files").resolve("ThemeSample.java"));
        String sampleSource = CodeView.readCode(sample);
        int sampleEntryLine = sampleSource.substring(0, sampleSource.indexOf("double ratio"))
                .split("\\n", -1).length;
        DecompiledSource decompiledSample = new DecompiledSource(
                sample,
                "sample.ThemeSample",
                sampleSource,
                SourceLineMap.fromOriginalToDisplayed(new int[]{sampleEntryLine, sampleEntryLine}),
                SourceVariableNames.empty(),
                null
        );
        Path sampleClasses = Files.createDirectories(root.resolve("TotalDebug/build/classes/java/main"));
        compileSampleSource(sample, sampleClasses);
        Path indexFile = root.resolve("classes.jindex");
        writeSampleClassIndex(indexFile, sampleClasses);
        CompanionProfile profile = new CompanionProfile(
                "ui-dev",
                root,
                workspace
        );
        CompanionApp.configureWithoutSession(profile, indexFile, List.of(sampleClasses), "ui-dev");
        writeSampleSource(sample);

        GlobalConfig.getInstance().loadFrom(root);
        String themeId = argument(args, "--theme=").orElse(null);
        if (themeId != null) {
            GlobalConfig.getInstance().setThemeId(themeId);
        }
        UiRenderScenario scenario = argument(args, "--scenario=")
                .map(UiRenderScenario::parse)
                .orElse(UiRenderScenario.MAIN);
        if (scenario == UiRenderScenario.BREAKPOINT_EDITOR) {
            CompanionApp.getDebuggerController().configureBreakpoint(
                    decompiledSample.debugSource(),
                    sampleEntryLine,
                    "count > 2",
                    ""
            ).join();
        }
        if (scenario == UiRenderScenario.DEBUGGER_LOCATION) {
            CompanionApp.getDebuggerController().configureBreakpoint(
                    decompiledSample.debugSource(),
                    sampleEntryLine + 2,
                    "",
                    ""
            ).join();
            CompanionApp.getDebuggerController().configureBreakpoint(
                    decompiledSample.debugSource(),
                    sampleEntryLine + 5,
                    "",
                    ""
            ).join();
        }
        Path screenshot = Arrays.stream(args)
                .filter(argument -> argument.startsWith("--screenshot="))
                .map(argument -> Path.of(argument.substring("--screenshot=".length())))
                .findFirst()
                .orElse(null);
        boolean backgroundMode = screenshot != null
                || Arrays.asList(args).contains("--verify-code-vision-click")
                || Arrays.asList(args).contains("--verify-gutter-click")
                || Arrays.asList(args).contains("--verify-gutter-direct")
                || Arrays.asList(args).contains("--verify-gutter-hover")
                || Arrays.asList(args).contains("--verify-hierarchy-row-layout")
                || Arrays.asList(args).contains("--verify-search-everywhere-interactions")
                || Arrays.asList(args).contains("--verify-method-navigation");
        CompanionApp.configureLookAndFeel();
        if (backgroundMode) {
            javax.swing.PopupFactory.setSharedInstance(new OffscreenPopupFactory());
        }

        System.out.println("UI dev harness data directory: " + root);
        System.out.println("theme: " + com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager.current().id());
        System.out.println("F9 = component inspector, F10 = UI defaults inspector");

        SwingUtilities.invokeAndWait(() -> {
            FlatInspector.install("F9");
            FlatUIDefaultsInspector.install("F10");
            MainWindow.INSTANCE.getEditorTabs().openEditorTab(new CodeView(MainWindow.INSTANCE.editorContext(), 
                    decompiledSample,
                    0,
                    EditorLocation.forRuntimeClass(
                            "com.github.minecraft_ta.totaldebug.ThemeSample",
                            sampleClasses.toUri().toASCIIString()
                    ), CompanionApp.currentRuntime()
            ));
            MainWindow.INSTANCE.getEditorTabs().openEditorTab(new ResourceView(MainWindow.INSTANCE.editorContext(), 
                    new ArchiveEntrySource(sampleArchive, "META-INF/MANIFEST.MF", -1), CompanionApp.currentRuntime()
            ));
            MainWindow.INSTANCE.getEditorTabs().openEditorTab(new ResourceView(MainWindow.INSTANCE.editorContext(), 
                    new ArchiveEntrySource(sampleArchive, "docs/NOTICE.custom", -1), CompanionApp.currentRuntime()
            ));
            MainWindow.INSTANCE.getEditorTabs().openEditorTab(new ResourceView(MainWindow.INSTANCE.editorContext(), 
                    new ArchiveEntrySource(sampleArchive, "config/defaults.toml", -1), CompanionApp.currentRuntime()
            ));
            MainWindow.INSTANCE.getEditorTabs().openEditorTab(new ResourceView(MainWindow.INSTANCE.editorContext(), 
                    new ArchiveEntrySource(sampleArchive, "assets/sample/textures/gui/debug.png", -1), CompanionApp.currentRuntime()
            ));
            boolean interactionVerification = Arrays.asList(args).stream()
                    .anyMatch(argument -> argument.startsWith("--verify-"));
            if (interactionVerification) {
                SwingUtilities.invokeLater(() -> MainWindow.INSTANCE.getEditorTabs().setSelectedIndex(0));
            }
            if (Arrays.asList(args).contains("--verify-code-vision-click")) {
                scheduleCodeVisionClickVerification(CodeView.readCode(sample));
            }
            if (Arrays.asList(args).contains("--verify-gutter-click")) {
                scheduleGutterClickVerification(CodeView.readCode(sample));
            }
            if (Arrays.asList(args).contains("--verify-gutter-direct")) {
                scheduleSingleGutterNavigationVerification(CodeView.readCode(sample));
            }
            boolean verifyGutterHover = Arrays.asList(args).contains("--verify-gutter-hover");
            boolean verifyHierarchyRowLayout = Arrays.asList(args).contains("--verify-hierarchy-row-layout");
            if (verifyGutterHover || verifyHierarchyRowLayout) {
                String source = CodeView.readCode(sample);
                int declarationOffset = verifyHierarchyRowLayout
                        ? source.indexOf(
                                "public void overrideMe(",
                                source.indexOf("class OverrideBase")
                        )
                        : source.indexOf("public interface ThemeSample");
                scheduleGutterHover(source, declarationOffset, verifyGutterHover);
                if (verifyHierarchyRowLayout) {
                    scheduleHierarchyRowLayoutVerification();
                }
            }
            if (Arrays.asList(args).contains("--cycle-themes")) {
                startThemeCycling();
            }
            MainWindow.INSTANCE.setSize(1280, 720);
            if (MainWindow.INSTANCE.isAutoRequestFocus()) {
                throw new IllegalStateException("Showing the main window must not automatically request focus");
            }
            boolean verifySearchEverywhere = Arrays.asList(args).contains(
                    "--verify-search-everywhere-interactions"
            );
            if (verifySearchEverywhere) {
                scheduleSearchEverywhereInteractionVerification();
            }
            if (Arrays.asList(args).contains("--verify-method-navigation")) {
                scheduleMethodNavigationVerification();
            }
            if (backgroundMode) {
                MainWindow.INSTANCE.setAutoRequestFocus(false);
                MainWindow.INSTANCE.setFocusableWindowState(false);
                MainWindow.INSTANCE.setLocation(-20_000, -20_000);
                MainWindow.INSTANCE.setVisible(true);
            } else {
                MainWindow.INSTANCE.setVisible(true);
                UIUtils.centerJFrame(MainWindow.INSTANCE, MainWindow.INSTANCE);
            }
            ToolTipManager.sharedInstance().setInitialDelay(200);
            MainWindow.INSTANCE.setRuntimeIndexStatus(new RuntimeIndexService.Status(
                    RuntimeIndexService.Phase.READY,
                    "Runtime index ready",
                    null
            ));
            if (!interactionVerification) {
                UiScenarioDriver.schedule(scenario, CodeView.readCode(sample), screenshot);
            }
        });
    }

    private static java.util.Optional<String> argument(String[] args, String prefix) {
        return Arrays.stream(args)
                .filter(argument -> argument.startsWith(prefix))
                .map(argument -> argument.substring(prefix.length()))
                .findFirst();
    }
}
