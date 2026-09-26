package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService.Side;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionText;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionValue;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectionPanelTest {
    private static final SubjectIdentity FURNACE = new SubjectIdentity(SubjectIdentity.Kind.BLOCK,
            "minecraft:furnace", "Furnace", "Minecraft", List.of(), "minecraft:furnace");
    private static final SubjectIdentity CHEST = new SubjectIdentity(SubjectIdentity.Kind.BLOCK,
            "minecraft:chest", "Chest", "Minecraft",
            List.of(new SubjectIdentity.ClassLink("Block", "net.minecraft.world.level.block.ChestBlock")),
            "minecraft:chest");
    private static final InspectSubjectPayload SUBJECT = new InspectSubjectPayload("game-session",
            "block minecraft:overworld 12 64 -3", FURNACE, "minecraft:item/furnace", Map.of());
    private static final List<FactSection> ITEMS = List.of(
            new FactSection("Items", List.of(Fact.stack("Slot 0", "minecraft:coal", 8, "Coal")), 1));

    @Test
    void aReadThatFailsPartWayStillShowsTheFactsItReported() throws Exception {
        withPanel(panel -> {
            ExecutionResult partial = ExecutionResult.failed("", null, "java.lang.IllegalStateException: broken")
                    .withFacts(ITEMS);

            panel.present(Side.SERVER, partial, null, text -> text);

            assertFalse(problemCard(panel).isVisible(), "facts are hidden behind the problem");
            assertTrue(labels(panel).stream().anyMatch(text -> text.equals("Items")), labels(panel)::toString);
            assertTrue(labels(panel).stream().anyMatch(text -> text.contains("IllegalStateException: broken")),
                    labels(panel)::toString);
        });
    }

    @Test
    void aFailedRefreshKeepsThePreviousReadAndMarksItStale() throws Exception {
        withPanel(panel -> {
            panel.present(Side.SERVER, ExecutionResult.failed("", null, "The chunk is not loaded"), null, t -> t);
            assertTrue(problemCard(panel).isVisible(), "without an earlier read the problem is shown");

            panel.present(Side.SERVER, completed(ITEMS), null, text -> text);
            assertFalse(problemCard(panel).isVisible());

            panel.present(Side.SERVER, ExecutionResult.failed("", null, "The chunk is not loaded"), null, t -> t);

            assertFalse(problemCard(panel).isVisible(), "the previous read stays on screen");
            assertTrue(labels(panel).contains("Showing the previous read. This one failed: The chunk is not loaded"),
                    labels(panel)::toString);
            assertTrue(labels(panel).contains("Items"), labels(panel)::toString);
        });
    }

    @Test
    void aReplacedBlockUpdatesTheHeaderAndSaysWhatItWas() throws Exception {
        withPanel(panel -> {
            panel.present(Side.SERVER, completed(ITEMS).withIdentity(CHEST), null, text -> text);

            assertEquals("Chest", panel.title());
            List<String> labels = labels(panel);
            assertTrue(labels.contains("Chest"), labels::toString);
            assertTrue(labels.stream().anyMatch(text -> text.startsWith("minecraft:chest")), labels::toString);
            assertTrue(labels.contains("Replaced: previously Furnace (minecraft:furnace)"), labels::toString);
            assertTrue(labels.contains("ChestBlock"), "the identity section links the current classes");
        });
    }

    @Test
    void theSameBlockKeepsItsHeaderWithoutANotice() throws Exception {
        withPanel(panel -> {
            panel.present(Side.SERVER, completed(ITEMS).withIdentity(FURNACE), null, text -> text);

            assertEquals("Furnace", panel.title());
            assertFalse(labels(panel).stream().anyMatch(text -> text.startsWith("Replaced")),
                    labels(panel)::toString);
        });
    }

    @Test
    void theIdentitySectionNamesWhereTheSubjectIsAndLinksItsClasses() {
        FactSection section = InspectionPanel.identitySection(CHEST,
                SubjectRef.parse("block minecraft:overworld 12 64 -3"));

        assertEquals("Block", section.title());
        assertEquals(List.of(
                Fact.text("ID", "minecraft:chest"),
                Fact.text("Mod", "Minecraft"),
                Fact.text("Position", "12, 64, -3 in minecraft:overworld"),
                Fact.text("Block", "ChestBlock")
                        .withLink(FactLink.toClass("net.minecraft.world.level.block.ChestBlock"))
        ), section.facts());
    }

    @Test
    void theUnsidedQueryIsNotPresentedAsAllSides() {
        assertEquals("Side: None", InspectionPanel.sideLabel(""));
        assertEquals("Side: North", InspectionPanel.sideLabel("NORTH"));
    }

    @Test
    void theReadRunsTheIsolatedReadersForTheSelectedSide() {
        assertTrue(InspectionPanel.readerSource("").contains("InspectionReaders.read(target(), null, facts());"));
        assertTrue(InspectionPanel.readerSource("NORTH")
                .contains("InspectionReaders.read(target(), Direction.NORTH, facts());"));
    }

    private static ExecutionResult completed(List<FactSection> facts) {
        ExecutionValue value = new ExecutionValue(
                new ExecutionText("java.lang.Boolean", 17, false),
                new ExecutionText("true", 4, false),
                new ExecutionText("", 0, false),
                ExecutionValue.Kind.BOOLEAN,
                0,
                0,
                false,
                List.of()
        );
        return new ExecutionResult(ExecutionStatus.RUN_COMPLETED, ExecutionText.empty(), value, ExecutionText.empty())
                .withFacts(facts);
    }

    private static void withPanel(Consumer<InspectionPanel> test) throws Exception {
        try (ItemIconService icons = new ItemIconService()) {
            Throwable[] failure = new Throwable[1];
            SwingUtilities.invokeAndWait(() -> {
                InspectionPanel panel = new InspectionPanel(SUBJECT,
                        () -> { throw new IllegalStateException("Tests run no snippets"); },
                        () -> { throw new IllegalStateException("Tests have no project"); },
                        icons, target -> { });
                try {
                    test.accept(panel);
                } catch (Throwable throwable) {
                    failure[0] = throwable;
                } finally {
                    panel.dispose();
                }
            });
            if (failure[0] instanceof Error error) throw error;
            if (failure[0] instanceof Exception exception) throw exception;
        }
    }

    private static Component problemCard(Container container) {
        for (Component child : container.getComponents()) {
            if (child.getClass() == JTextArea.class) return child.getParent().getParent();
            if (child instanceof Container nested) {
                Component found = problemCard(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<String> labels(Container container) {
        List<String> labels = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label && label.isVisible() && label.getText() != null) {
                labels.add(label.getText());
            }
            if (child instanceof Container nested) labels.addAll(labels(nested));
        }
        return labels;
    }
}
