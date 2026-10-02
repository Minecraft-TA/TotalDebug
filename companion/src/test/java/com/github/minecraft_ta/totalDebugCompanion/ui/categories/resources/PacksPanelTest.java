package com.github.minecraft_ta.totalDebugCompanion.ui.categories.resources;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ListedPack;
import com.github.minecraft_ta.totalDebugCompanion.change.Effect;
import com.github.minecraft_ta.totalDebugCompanion.pack.PackSelections;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacksPanelTest {
    @Test
    void packsAreEnabledAndOrderedUnderTheGamesRulesUntilApplied() throws Exception {
        List<List<String>> applied = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            PacksPanel panel = new PacksPanel(PacksPanel.Side.DATA, target -> { });
            panel.setApplier(enabled -> {
                applied.add(enabled);
                return CompletableFuture.completedFuture(new PackSelections.Applied(Effect.WORLD_OPENS));
            }, "");
            panel.setPacks(List.of(
                    new ListedPack("mod_data", ListedPack.State.ENABLED, null, "", Set.of(ListedPack.Rule.REQUIRED)),
                    new ListedPack("file/Tweaks", ListedPack.State.ENABLED, null),
                    new ListedPack("vanilla", ListedPack.State.ENABLED, null),
                    new ListedPack("file/Fresh", ListedPack.State.DISABLED, null),
                    new ListedPack("bundle", ListedPack.State.DISABLED, null, "", Set.of(ListedPack.Rule.MISSING_FEATURES))), null);
            assertEquals(List.of("+mod_data", "+file/Tweaks", "+vanilla", "-file/Fresh", "-bundle"), panel.shownIds());

            select(panel, 0);
            press(panel, "togglePacks");
            select(panel, 4);
            press(panel, "togglePacks");
            assertEquals(List.of("+mod_data", "+file/Tweaks", "+vanilla", "-file/Fresh", "-bundle"), panel.shownIds(),
                    "a required pack stays enabled, and one needing features the world lacks cannot be enabled");
            assertFalse(panel.changed());

            select(panel, 3);
            press(panel, "togglePacks");
            assertEquals(List.of("+file/Fresh", "+mod_data", "+file/Tweaks", "+vanilla", "-bundle"), panel.shownIds(),
                    "enabled at the top, as the pack screen adds it");
            select(panel, 3);
            press(panel, "movePackUp");
            press(panel, "movePackUp");
            assertEquals(List.of("+file/Fresh", "+vanilla", "+mod_data", "+file/Tweaks", "-bundle"), panel.shownIds());
            select(panel, 0);
            press(panel, "movePackUp");
            assertEquals("+file/Fresh", panel.shownIds().getFirst(), "nothing is above the top");
            assertTrue(panel.changed());

            panel.applyChanges();
        });
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(List.of(List.of("file/Tweaks", "mod_data", "vanilla", "file/Fresh")), applied, "lowest first, as the game keeps it");
    }

    @Test
    void discardGoesBackToThePacksAsListed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PacksPanel panel = new PacksPanel(PacksPanel.Side.RESOURCES, target -> { });
            panel.setApplier(enabled -> CompletableFuture.failedFuture(new IllegalStateException("unused")), "");
            panel.setPacks(List.of(new ListedPack("file/A", ListedPack.State.ENABLED, null),
                    new ListedPack("file/B", ListedPack.State.DISABLED, null)), null);
            select(panel, 0);
            press(panel, "togglePacks");
            assertEquals(List.of("-file/A", "-file/B"), panel.shownIds());
            panel.discardChanges();
            assertEquals(List.of("+file/A", "-file/B"), panel.shownIds());
            assertFalse(panel.changed());
        });
    }

    private static void select(PacksPanel panel, int row) {
        panel.table().setRowSelectionInterval(row, row);
    }

    private static void press(PacksPanel panel, String action) {
        panel.table().getActionMap().get(action).actionPerformed(new ActionEvent(panel.table(), ActionEvent.ACTION_PERFORMED, action));
    }
}
