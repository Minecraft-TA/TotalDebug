package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

class ResourceTextEditorTest {
    private static final String LANG = "assets/testmod/lang/en_us.json";

    @TempDir Path directory;

    @Test
    void aRevertElsewhereShowsTheOpenedFileAgain() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = new ResourceEdits(this.directory, record,
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run, () -> false);
        edits.packStack(new PackStackPayload(34, 48, List.of(new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", "")),
                List.of()));
        String saved = "{\"a\":\"saved\"}";
        String inJar = "{\"a\":\"jar\"}";
        edits.save(LANG, saved.getBytes(StandardCharsets.UTF_8)).get(5, TimeUnit.SECONDS);

        ResourceTextEditor[] editor = new ResourceTextEditor[1];
        SwingUtilities.invokeAndWait(() -> editor[0] = new ResourceTextEditor(LANG, "testmod.jar", null,
                new LoadedResource.Text(inJar, "text/json", "UTF-8", inJar.length()), edits));
        try {
            awaitOnSwing(() -> editor[0].textPanel().text().equals(saved));

            // The Changes page reverts the resource while its tab is open.
            edits.revert(record.changes().getFirst()).get(5, TimeUnit.SECONDS);
            awaitOnSwing(() -> editor[0].textPanel().text().equals(inJar));
        } finally {
            SwingUtilities.invokeAndWait(editor[0]::dispose);
        }
    }

    private static void awaitOnSwing(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean[] met = new boolean[1];
        while (true) {
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) return;
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting on the Swing thread");
            Thread.sleep(20);
        }
    }
}
