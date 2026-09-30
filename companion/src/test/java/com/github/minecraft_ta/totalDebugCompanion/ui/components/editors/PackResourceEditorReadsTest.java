package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEditsFixture;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.testui.UiTestScope;
import com.github.minecraft_ta.totaldebug.protocol.message.ClientPacksPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The resource editor's reads, with a read paused while something else happens, as docs/SYSTEMS.md asks: a hidden tab
 * reads nothing more, also to check its read against a later change, and reads once when shown again.
 */
@UiTest
class PackResourceEditorReadsTest {
    private static final String LANG = "assets/testmod/lang/en_us.json";

    @TempDir Path directory;

    @Test
    void aTabHiddenWhileItReadsChecksAChangeElsewhereOnlyWhenShownAgain() throws Exception {
        ResourceEdits edits = ResourceEditsFixture.edits(GameLocations.of(this.directory, false), ChangeRecord.inMemory(),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run, InstanceState.inMemory());
        edits.packs().named(new ClientPacksPayload(new PackStackPayload(34, List.of(new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", ""))), 48));
        edits.save(LANG, "{\"a\":\"saved\"}".getBytes(StandardCharsets.UTF_8)).get(5, TimeUnit.SECONDS);

        GatedEditor[] editor = new GatedEditor[1];
        SwingUtilities.invokeAndWait(() -> editor[0] = new GatedEditor(edits));
        try {
            SwingUtilities.invokeAndWait(() -> UiTestScope.showPages(editor[0]));
            UiTestScope.await(() -> editor[0].decodes.get() == 1);

            // The first read waits in its decode while the tab is left and another tab saves the same file.
            SwingUtilities.invokeAndWait(() -> editor[0].setVisible(false));
            edits.save(LANG, "{\"a\":\"elsewhere\"}".getBytes(StandardCharsets.UTF_8)).get(5, TimeUnit.SECONDS);
            editor[0].gate.countDown();
            editor[0].reading().get(5, TimeUnit.SECONDS);
            for (int step = 0; step < 3; step++) SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(200);
            assertEquals(1, editor[0].decodes.get(), "hidden, the tab reads nothing more, also to check its read");

            SwingUtilities.invokeAndWait(() -> editor[0].setVisible(true));
            UiTestScope.await(() -> "{\"a\":\"elsewhere\"}".equals(editor[0].content));
            assertEquals(2, editor[0].decodes.get(), "shown again, it reads the other tab's save once");
        } finally {
            SwingUtilities.invokeAndWait(() -> editor[0].dispose());
        }
    }

    /** An editor of text whose first decode waits until the test lets it go. */
    private static final class GatedEditor extends PackResourceEditor<String> {
        final CountDownLatch gate = new CountDownLatch(1);
        final AtomicInteger decodes = new AtomicInteger();
        volatile String content = "{}";
        private String saved = "{}";

        GatedEditor(ResourceEdits edits) {
            super(LANG, "testmod.jar", null, "{}", edits);
            start(new JPanel());
        }

        @Override protected String shown() { return this.content; }
        @Override protected boolean same(String first, String second) { return Objects.equals(first, second); }
        @Override protected void load(String content) { this.content = content; this.saved = content; }
        @Override protected void markSaved(String content) { this.saved = content; }
        @Override protected boolean modified() { return !this.content.equals(this.saved); }
        @Override protected String none() { return ""; }
        @Override protected String noun() { return "text"; }
        @Override protected void setEditable(boolean editable) { }
        @Override protected byte[] encode(String content) { return content.getBytes(StandardCharsets.UTF_8); }

        @Override
        protected String decode(byte[] bytes) throws IOException {
            if (this.decodes.incrementAndGet() == 1) {
                try {
                    this.gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
