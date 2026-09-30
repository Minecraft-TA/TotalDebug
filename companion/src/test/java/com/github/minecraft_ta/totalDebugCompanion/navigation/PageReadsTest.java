package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApplication;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogFixtures;
import com.github.minecraft_ta.totalDebugCompanion.model.KeyBindingsView;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.KeyBindingsPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Pages opened the way a click opens them read once, and again only after a change they follow (docs/SYSTEMS.md, Tests):
 * built by the application, opened through navigation, shown in the window.
 */
@UiTest
class PageReadsTest {
    @TempDir Path directory;

    @Test
    void theKeyBindingsPageReadsOnceWhenOpenedAndAgainOnlyAfterAChangeItMissed() throws Exception {
        Path home = Files.createDirectory(this.directory.resolve("home"));
        GlobalConfig.getInstance().loadFrom(home);
        Path game = Files.createDirectory(this.directory.resolve("game"));
        Files.writeString(game.resolve("options.txt"), "key_key.drop:key.keyboard.q\n");
        try (CompanionApplication app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(game)).get(10, TimeUnit.SECONDS);
            ProjectScope scope = app.currentScope();
            CatalogFixtures.catalog(CatalogFixtures.modJar(this.directory)).write(scope.paths().catalog());
            scope.catalog().accept(CatalogFixtures.INVENTORY, scope.paths().catalog(), Runnable::run);
            MainWindow window = UiTestScope.onEdt(app::createWindow);
            UiTestScope.onEdt(() -> {
                window.setSize(1280, 720);
                UiTestScope.show(window);
            });

            open(window, new NavigationTarget.KeyBindings(""));
            KeyBindingsPanel panel = UiTestScope.onEdt(() -> (KeyBindingsPanel) assertInstanceOf(KeyBindingsView.class,
                    window.getEditorTabs().getSelectedEditor()).getComponent());
            UiTestScope.await(() -> panel.reads() == 1);
            settle();
            assertEquals(1, reads(panel), "opening the page reads it once");

            open(window, new NavigationTarget.KeyBindings("key.drop"));
            assertEquals(1, reads(panel), "navigating to the page it shows reads nothing");

            open(window, new NavigationTarget.Changes());
            scope.keyBindings().assignmentsChanged().fire();
            settle();
            assertEquals(1, reads(panel), "a hidden page does not read");
            open(window, new NavigationTarget.KeyBindings(""));
            UiTestScope.await(() -> panel.reads() == 2);
            settle();
            assertEquals(2, reads(panel), "shown again, it reads the change it missed once");

            open(window, new NavigationTarget.Changes());
            open(window, new NavigationTarget.KeyBindings(""));
            assertEquals(2, reads(panel), "shown again without a change, it reads nothing");
        }
    }

    private static void open(MainWindow window, NavigationTarget target) throws Exception {
        window.navigation().navigate(target, NavigationService.Activation.KEEP_CURRENT_WINDOW).get(5, TimeUnit.SECONDS);
        settle();
    }

    private static int reads(KeyBindingsPanel panel) throws Exception {
        return UiTestScope.onEdt(panel::reads);
    }

    /** Lets the Swing steps queued by showing and reading run, and a read they started finish. */
    private static void settle() throws Exception {
        for (int step = 0; step < 3; step++) SwingUtilities.invokeAndWait(() -> { });
        Thread.sleep(200);
        SwingUtilities.invokeAndWait(() -> { });
    }
}
