package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
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
import java.util.concurrent.CopyOnWriteArrayList;
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
        ResourceEdits edits = new ResourceEdits(GameLocations.of(this.directory, false), record,
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run,
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
        ResourceEdits edits = new ResourceEdits(GameLocations.of(this.directory, false), record,
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run,
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
        ResourceEdits edits = new ResourceEdits(GameLocations.of(this.directory, false), ChangeRecord.inMemory(),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run,
                InstanceState.inMemory());
        edits.packStack(new PackStackPayload(34, 48, List.of(new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", "")),
                List.of()));
        Path mine = Files.createDirectories(this.directory.resolve("resourcepacks/MyPack"));
        Files.writeString(mine.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"\"}}");
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
                assertFalse(editor[0].textPanel().editorPane.isEditable(), "the chosen pack's copy is read before typing goes on");
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
        ResourceEdits edits = new ResourceEdits(GameLocations.of(this.directory, false), ChangeRecord.inMemory(),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run,
                InstanceState.inMemory());
        PackStackPayload.Pack managed = new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", "");
        edits.packStack(new PackStackPayload(34, 48, List.of(managed), List.of()));
        Path mine = Files.createDirectories(this.directory.resolve("resourcepacks/MyPack"));
        Files.writeString(mine.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"\"}}");
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

    @Test
    void aSaveOverTextAnotherTabSavedSinceAsksFirst() throws Exception {
        ResourceEdits edits = new ResourceEdits(GameLocations.of(this.directory, false), ChangeRecord.inMemory(),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run,
                InstanceState.inMemory());
        edits.packStack(new PackStackPayload(34, 48, List.of(new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", "")),
                List.of()));
        String inJar = "{\"a\":\"jar\"}";
        ResourceTextEditor[] tabs = new ResourceTextEditor[2];
        SwingUtilities.invokeAndWait(() -> {
            for (int tab = 0; tab < 2; tab++) {
                tabs[tab] = new ResourceTextEditor(LANG, "testmod.jar", null,
                        new LoadedResource.Text(inJar, "text/json", "UTF-8", inJar.length()), edits);
            }
        });
        try {
            awaitOnSwing(() -> tabs[0].targetBox().getItemCount() > 0 && tabs[1].targetBox().getItemCount() > 0);
            List<String> asked = new CopyOnWriteArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                tabs[1].textPanel().editorPane.setText("{\"a\":\"second\"}");
                tabs[1].askToReplace = changed -> {
                    asked.add(changed.getMessage());
                    return false;
                };
                tabs[0].textPanel().editorPane.setText("{\"a\":\"first\"}");
                tabs[0].save();
            });
            Path file = edits.pack(LANG).resolve(LANG);
            awaitOnSwing(() -> Files.isRegularFile(file) && tabs[1].noticeText().contains("changed in the pack"));
            SwingUtilities.invokeAndWait(tabs[1]::save);
            awaitOnSwing(() -> !asked.isEmpty());
            assertEquals("{\"a\":\"first\"}", Files.readString(file), "the other tab's save stays until overwriting is chosen");
            assertTrue(asked.getFirst().startsWith("en_us.json changed in the TotalDebug resource pack"), asked.getFirst());

            SwingUtilities.invokeAndWait(() -> {
                tabs[1].askToReplace = changed -> true;
                tabs[1].save();
            });
            awaitOnSwing(() -> {
                try {
                    return Files.readString(file).equals("{\"a\":\"second\"}");
                } catch (Exception unreadable) {
                    return false;
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                tabs[0].dispose();
                tabs[1].dispose();
            });
        }
    }

    @Test
    void aTabWhoseChangesAnotherTabSavedSavesItsNextChangeWithoutAsking() throws Exception {
        ResourceEdits edits = new ResourceEdits(GameLocations.of(this.directory, false), ChangeRecord.inMemory(),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run,
                InstanceState.inMemory());
        edits.packStack(new PackStackPayload(34, 48, List.of(new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", "")),
                List.of()));
        String inJar = "{\"a\":\"jar\"}";
        ResourceTextEditor[] tabs = new ResourceTextEditor[2];
        SwingUtilities.invokeAndWait(() -> {
            for (int tab = 0; tab < 2; tab++) {
                tabs[tab] = new ResourceTextEditor(LANG, "testmod.jar", null,
                        new LoadedResource.Text(inJar, "text/json", "UTF-8", inJar.length()), edits);
            }
        });
        try {
            awaitOnSwing(() -> tabs[0].targetBox().getItemCount() > 0 && tabs[1].targetBox().getItemCount() > 0);
            List<String> asked = new CopyOnWriteArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                tabs[1].askToReplace = changed -> asked.add(changed.getMessage());
                tabs[1].textPanel().editorPane.setText("{\"a\":\"same\"}");
                tabs[0].textPanel().editorPane.setText("{\"a\":\"same\"}");
                tabs[0].save();
            });
            Path file = edits.pack(LANG).resolve(LANG);
            // The other tab's save matches this tab's text, which is then unsaved no more.
            awaitOnSwing(() -> Files.isRegularFile(file) && !tabs[1].textPanel().modified());
            SwingUtilities.invokeAndWait(() -> {
                tabs[1].textPanel().editorPane.setText("{\"a\":\"next\"}");
                tabs[1].save();
            });
            awaitOnSwing(() -> {
                try {
                    return Files.readString(file).equals("{\"a\":\"next\"}");
                } catch (Exception unreadable) {
                    return false;
                }
            });
            assertTrue(asked.isEmpty(), "the tab had read the copy it replaced: " + asked);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                tabs[0].dispose();
                tabs[1].dispose();
            });
        }
    }

    @Test
    void changesCarriedToAnotherPackAreSavedThereWithoutAsking() throws Exception {
        ResourceEdits edits = new ResourceEdits(GameLocations.of(this.directory, false), ChangeRecord.inMemory(),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run,
                InstanceState.inMemory());
        edits.packStack(new PackStackPayload(34, 48, List.of(new PackStackPayload.Pack(ResourceEdits.PACK_ID, "TotalDebug", "")),
                List.of()));
        edits.save(LANG, "{}".getBytes(StandardCharsets.UTF_8)).get(5, TimeUnit.SECONDS);
        Path mine = Files.createDirectories(this.directory.resolve("resourcepacks/MyPack"));
        Files.writeString(mine.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"\"}}");
        String inJar = "{\"a\":\"jar\"}";
        ResourceTextEditor[] editor = new ResourceTextEditor[1];
        SwingUtilities.invokeAndWait(() -> editor[0] = new ResourceTextEditor(LANG, "testmod.jar", null,
                new LoadedResource.Text(inJar, "text/json", "UTF-8", inJar.length()), edits));
        try {
            awaitOnSwing(() -> editor[0].targetBox().getItemCount() == 2);
            List<String> asked = new CopyOnWriteArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                editor[0].askToReplace = changed -> asked.add(changed.getMessage());
                editor[0].textPanel().editorPane.setText("{\"a\":\"carried\"}");
                editor[0].targetBox().setSelectedItem(mine);
            });
            awaitOnSwing(() -> {
                editor[0].save();
                return Files.isRegularFile(mine.resolve(LANG));
            });
            assertEquals("{\"a\":\"carried\"}", Files.readString(mine.resolve(LANG)));
            assertEquals(List.of(), asked, "the other pack's copy is the one the carried changes replace");
        } finally {
            SwingUtilities.invokeAndWait(editor[0]::dispose);
        }
    }

    @Test
    void aMinifiedLanguageFileIsReformattedAsOneEditThatUndoTakesBack() throws Exception {
        ResourceEdits edits = new ResourceEdits(GameLocations.of(this.directory, false), ChangeRecord.inMemory(),
                new ResourceOriginals(this.directory.resolve("total-debug/originals")), Runnable::run,
                InstanceState.inMemory());
        StringBuilder minified = new StringBuilder("{");
        for (int entry = 0; entry < 100; entry++) minified.append("\"item.testmod.gear_").append(entry).append("\":\"Gear\",");
        minified.append("\"end\":\"end\"}\n");
        String inJar = minified.toString();
        ResourceTextEditor[] editor = new ResourceTextEditor[1];
        SwingUtilities.invokeAndWait(() -> {
            editor[0] = new ResourceTextEditor(LANG, "testmod.jar", null,
                    new LoadedResource.Text(inJar, "text/json", "UTF-8", inJar.length()), edits);
            editor[0].reformat();
            assertEquals(inJar, editor[0].textPanel().text(), "until the working pack's copy is read, the text stays");
        });
        try {
            awaitOnSwing(() -> editor[0].targetBox().getItemCount() > 0);
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(editor[0].oneLineOffered(), "one long line, ending in a line break, offers Reformat Code");
                editor[0].textPanel().editorPane.insert("//", 0);
                assertTrue(editor[0].oneLineOffered(), "typing leaves the offer, so the text does not move");
                editor[0].reformat();
            });
            awaitOnSwing(() -> editor[0].noticeText().startsWith("Not reformatted: "));
            SwingUtilities.invokeAndWait(() -> {
                editor[0].textPanel().editorPane.replaceRange("", 0, 2);
                assertEquals("", editor[0].noticeText(), "the problem ends with the edit");
                editor[0].reformat();
            });
            awaitOnSwing(() -> editor[0].textPanel().text().startsWith("{\n  \"item.testmod.gear_0\": \"Gear\",\n"));
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(editor[0].textPanel().modified(), "an edit that Save writes");
                assertFalse(editor[0].oneLineOffered());
                editor[0].textPanel().editorPane.undoLastAction();
                assertEquals(inJar, editor[0].textPanel().text(), "one Undo takes it back");
            });
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
