package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApplication;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totaldebug.protocol.message.ClientPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A mod's resource opened the way a click opens it reads its pack's copy once, and again only after a change it missed
 * (docs/SYSTEMS.md, Tests): built by the application, opened through navigation, shown in the window.
 */
@UiTest
class ResourceTabReadsTest {
    private static final String LANG = "assets/testmod/lang/en_us.json";

    @TempDir Path directory;

    @Test
    void aResourceTabReadsOnceWhenOpenedAndAgainOnlyAfterAChangeItMissed() throws Exception {
        Path home = Files.createDirectory(this.directory.resolve("home"));
        GlobalConfig.getInstance().loadFrom(home);
        Path game = Files.createDirectory(this.directory.resolve("game"));
        Path jar = this.directory.resolve("testmod.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(LANG));
            zip.write("{\"a\":\"jar\"}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        try (CompanionApplication app = new CompanionApplication(new CompanionLaunchConfiguration(home), "test-token")) {
            app.openProject(CompanionProfile.forGame(game)).get(10, TimeUnit.SECONDS);
            ProjectScope scope = app.currentScope();
            // As the connected game names its packs, whose format a first save into the managed pack needs.
            scope.packs().named(new ClientPacksPayload(new PackStackPayload(34,
                    List.of(new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", ""))), 48));
            MainWindow window = UiTestScope.onEdt(app::createWindow);
            UiTestScope.onEdt(() -> {
                window.setSize(1280, 720);
                UiTestScope.show(window);
            });
            NavigationTarget resource = new NavigationTarget.ArchiveEntry(jar, LANG);

            open(window, resource);
            PackResourceEditor<?> editor = UiTestScope.onEdt(() ->
                    ((ResourceViewPanel) window.getEditorTabs().getSelectedEditor().getComponent()).editor());
            assertNotNull(editor, "a mod's language file opens in its editor");
            UiTestScope.await(() -> editor.reads() == 1);
            editor.reading().get(5, TimeUnit.SECONDS);
            settle();
            assertEquals(1, reads(editor), "opening the tab reads the pack's copy once");

            open(window, resource);
            assertEquals(1, reads(editor), "navigating to the tab it shows reads nothing");

            open(window, new NavigationTarget.Changes());
            scope.resources().save(LANG, "{\"a\":\"elsewhere\"}".getBytes(StandardCharsets.UTF_8)).get(10, TimeUnit.SECONDS);
            settle();
            assertEquals(1, reads(editor), "a hidden tab does not read");
            open(window, resource);
            UiTestScope.await(() -> editor.reads() == 2);
            editor.reading().get(5, TimeUnit.SECONDS);
            settle();
            assertEquals(2, reads(editor), "shown again, it reads the save it missed once");

            open(window, new NavigationTarget.Changes());
            open(window, resource);
            assertEquals(2, reads(editor), "shown again without a change, it reads nothing");
        }
    }

    private static void open(MainWindow window, NavigationTarget target) throws Exception {
        window.navigation().navigate(target, NavigationService.Activation.KEEP_CURRENT_WINDOW).get(10, TimeUnit.SECONDS);
        settle();
    }

    private static int reads(PackResourceEditor<?> editor) throws Exception {
        return UiTestScope.onEdt(editor::reads);
    }

    /** Lets the Swing steps queued by showing and reading run, and a read they started finish. */
    private static void settle() throws Exception {
        for (int step = 0; step < 3; step++) SwingUtilities.invokeAndWait(() -> { });
        Thread.sleep(200);
        SwingUtilities.invokeAndWait(() -> { });
    }
}
