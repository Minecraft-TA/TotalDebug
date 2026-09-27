package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.pack.ResourceEdits;
import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.ResourceOriginals;
import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceTextEditorTest {
    private static final String LANG = "assets/testmod/lang/en_us.json";

    @TempDir Path directory;

    @Test
    void aRevertElsewhereShowsTheOpenedFileAgain() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = new ResourceEdits(this.directory, record,
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run, () -> false,
                InstanceState.inMemory());
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

    @Test
    void aDeletedFileOfTheManagedPackStaysAsUnsavedText() throws Exception {
        ChangeRecord record = ChangeRecord.inMemory();
        ResourceEdits edits = new ResourceEdits(this.directory, record,
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run, () -> false,
                InstanceState.inMemory());
        edits.packStack(new PackStackPayload(34, 48, List.of(new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", "")),
                List.of()));
        String added = "{\"a\":\"added\"}";
        Path pack = edits.save(LANG, added.getBytes(StandardCharsets.UTF_8)).get(5, TimeUnit.SECONDS).pack();

        // Opened from the Changes page: the file of the managed pack itself.
        ResourceTextEditor[] editor = new ResourceTextEditor[1];
        SwingUtilities.invokeAndWait(() -> editor[0] = new ResourceTextEditor(LANG, "en_us.json", pack,
                new LoadedResource.Text(added, "text/json", "UTF-8", added.length()), edits));
        try {
            edits.revert(record.changes().getFirst()).get(5, TimeUnit.SECONDS);
            awaitOnSwing(() -> editor[0].textPanel().modified());
            SwingUtilities.invokeAndWait(() -> assertEquals(added, editor[0].textPanel().text(),
                    "the text stays on screen, unsaved, instead of passing for the pack's copy"));
        } finally {
            SwingUtilities.invokeAndWait(editor[0]::dispose);
        }
    }

    @Test
    void aSaveRightAfterChoosingAnotherPackGoesIntoThatPack() throws Exception {
        ResourceEdits edits = new ResourceEdits(this.directory, ChangeRecord.inMemory(),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run, () -> false,
                InstanceState.inMemory());
        edits.packStack(new PackStackPayload(34, 48, List.of(new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", "")),
                List.of()));
        Path mine = Files.createDirectories(this.directory.resolve("resourcepacks/MyPack"));
        Files.writeString(mine.resolve("pack.mcmeta"), "{}");
        Path managed = edits.pack(LANG);
        String inJar = "{\"a\":\"jar\"}";
        String edited = "{\"a\":\"edited\"}";

        ResourceTextEditor[] editor = new ResourceTextEditor[1];
        SwingUtilities.invokeAndWait(() -> {
            editor[0] = new ResourceTextEditor(LANG, "testmod.jar", null,
                    new LoadedResource.Text(inJar, "text/json", "UTF-8", inJar.length()), edits);
            assertFalse(editor[0].textPanel().editorPane.isEditable(),
                    "until the working pack's copy is read, typing would edit the mod's text and save it over that copy");
        });
        try {
            awaitOnSwing(() -> editor[0].targetBox().getItemCount() == 2);
            SwingUtilities.invokeAndWait(() -> assertTrue(editor[0].textPanel().editorPane.isEditable()));
            SwingUtilities.invokeAndWait(() -> {
                editor[0].textPanel().editorPane.setText(edited);
                editor[0].targetBox().setSelectedItem(mine);
                // Before the chosen pack's copy is read, the tab still shows the last pack's.
                editor[0].save();
            });
            awaitOnSwing(() -> {
                editor[0].save();
                return Files.isRegularFile(mine.resolve(LANG));
            });
            assertEquals(edited, Files.readString(mine.resolve(LANG)));
            assertFalse(Files.exists(managed.resolve(LANG)), "nothing is saved into the pack chosen before");
        } finally {
            SwingUtilities.invokeAndWait(editor[0]::dispose);
        }
    }

    @Test
    void theWarningThatTheGameDoesNotUseTheCopyEndsWhenItDoes() throws Exception {
        ResourceEdits edits = new ResourceEdits(this.directory, ChangeRecord.inMemory(),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run, () -> false,
                InstanceState.inMemory());
        PackStackPayload.Pack managed = new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", "");
        edits.packStack(new PackStackPayload(34, 48, List.of(managed), List.of()));
        Path mine = Files.createDirectories(this.directory.resolve("resourcepacks/MyPack"));
        Files.writeString(mine.resolve("pack.mcmeta"), "{}");
        edits.setWorkingPack(LANG, mine);
        String inJar = "{\"a\":\"jar\"}";

        ResourceTextEditor[] editor = new ResourceTextEditor[1];
        SwingUtilities.invokeAndWait(() -> editor[0] = new ResourceTextEditor(LANG, "testmod.jar", null,
                new LoadedResource.Text(inJar, "text/json", "UTF-8", inJar.length()), edits));
        try {
            awaitOnSwing(() -> editor[0].noticeText().contains("not enabled"));
            // The player enables the pack in the game.
            edits.packStack(new PackStackPayload(34, 48, List.of(managed,
                    new PackStackPayload.Pack("file/MyPack", "MyPack", mine.toString())), List.of()));
            awaitOnSwing(() -> editor[0].noticeText().isEmpty());
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
