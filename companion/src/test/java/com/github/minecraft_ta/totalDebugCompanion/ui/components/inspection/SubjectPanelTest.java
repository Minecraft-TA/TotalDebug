package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.catalog.PackCatalogService;
import com.github.minecraft_ta.totalDebugCompanion.catalog.RegistryIds;
import com.github.minecraft_ta.totalDebugCompanion.inspection.InspectionSession;
import com.github.minecraft_ta.totalDebugCompanion.inspection.InspectionTool;
import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog.DefinitionDetails;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.subject.LinkLabel;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionText;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionValue;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactData;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubjectPanelTest {
    @TempDir
    static Path directory;

    private static final SubjectIdentity FURNACE = new SubjectIdentity(SubjectIdentity.Kind.BLOCK,
            "minecraft:furnace", "Furnace", "Minecraft", List.of(), "minecraft:furnace");
    private static final SubjectIdentity CHEST = new SubjectIdentity(SubjectIdentity.Kind.BLOCK,
            "minecraft:chest", "Chest", "Minecraft",
            List.of(new SubjectIdentity.ClassLink("Class", "net.minecraft.world.level.block.ChestBlock")),
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

            panel.session().present(partial, null, text -> text);

            assertFalse(problemCard(panel).isVisible(), "facts are hidden behind the problem");
            assertTrue(labels(panel).stream().anyMatch(text -> text.equals("Items")), labels(panel)::toString);
            assertTrue(labels(panel).stream().anyMatch(text -> text.contains("IllegalStateException: broken")),
                    labels(panel)::toString);
        });
    }

    @Test
    void aFailedRefreshKeepsThePreviousReadAndMarksItStale() throws Exception {
        withPanel(panel -> {
            panel.session().present(ExecutionResult.failed("", null, "The chunk is not loaded"), null, t -> t);
            assertTrue(problemCard(panel).isVisible(), "without an earlier read the problem is shown");

            panel.session().present(completed(ITEMS), null, text -> text);
            assertFalse(problemCard(panel).isVisible());

            panel.session().present(ExecutionResult.failed("", null, "The chunk is not loaded"), null, t -> t);

            assertFalse(problemCard(panel).isVisible(), "the previous read stays on screen");
            assertTrue(labels(panel).contains("Showing the previous read. This one failed: The chunk is not loaded"),
                    labels(panel)::toString);
            assertTrue(labels(panel).contains("Items"), labels(panel)::toString);
        });
    }

    @Test
    void aReplacedBlockUpdatesTheHeaderAndSaysWhatItWas() throws Exception {
        withPanel(panel -> {
            panel.session().present(completed(ITEMS).withIdentity(CHEST), null, text -> text);

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
            panel.session().present(completed(ITEMS).withIdentity(FURNACE), null, text -> text);

            assertEquals("Furnace", panel.title());
            assertFalse(labels(panel).stream().anyMatch(text -> text.startsWith("Replaced")),
                    labels(panel)::toString);
        });
    }

    @Test
    void theIdentitySectionNamesTheClassesAndRelatedEntriesAndLinksTheDefinitionFromTheGame() {
        SubjectRef.Definition chest = new SubjectRef.Definition(RegistryIds.BLOCK, "minecraft:chest");
        Fact block = Fact.text("Class", "ChestBlock").withLink(FactLink.toClass("net.minecraft.world.level.block.ChestBlock"));
        Fact item = Fact.text("Item", "Chest").withLink(FactLink.toSubject(new SubjectRef.Definition(RegistryIds.ITEM, "minecraft:chest")));

        FactSection live = SubjectPanel.identitySection("Block", chest, "Chest", "Chest", List.of(block), List.of(item), true);
        FactSection definition = SubjectPanel.identitySection("Block", chest, "Chest", "Chest", List.of(block), List.of(item), false);
        FactSection renamed = SubjectPanel.identitySection("Block", chest, "Loot", "Chest", List.of(), List.of(), true);

        assertEquals("Block", live.title());
        assertEquals(List.of(Fact.text("ID", "minecraft:chest").withLink(FactLink.toSubject(chest)), block, item), live.facts());
        assertEquals(Fact.text("ID", "minecraft:chest"), definition.facts().getFirst(), "a definition's page does not link itself");
        assertEquals(Fact.text("Name", "Chest"), renamed.facts().get(1), "a renamed subject names its definition");
    }

    @Test
    void theHeaderSaysWhereTheSubjectIsAndLinksItsMod() throws Exception {
        List<NavigationTarget> opened = new ArrayList<>();
        withPanel(opened::add, panel -> {
            List<String> labels = labels(panel);
            assertTrue(labels.containsAll(List.of("Furnace", "Block", "12, 64, -3", "minecraft:overworld", "Minecraft")),
                    labels::toString);
            for (LinkLabel link : links(panel)) {
                link.dispatchEvent(new MouseEvent(link, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1));
            }
        });
        assertEquals(List.of(new NavigationTarget.ModPage("minecraft")), opened);
    }

    @Test
    void aReadWithDataStaysOnTheOverview() throws Exception {
        withPanel(panel -> {
            FactSection nbt = new FactSection("NBT", List.of(Fact.data("Stack", FactData.of(
                    new byte[]{10, 3, 0, 5, 'C', 'o', 'u', 'n', 't', 0, 0, 0, 7, 0}, List.of()))), 1);
            panel.session().present(completed(List.of(nbt)), null, text -> text);
            panel.session().present(completed(List.of(nbt)), null, text -> text);

            JTabbedPane views = views(panel);
            assertEquals("Overview", views.getTitleAt(views.getSelectedIndex()));
        });
    }

    private static JTabbedPane views(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JTabbedPane tabs) return tabs;
            if (child instanceof Container nested) {
                JTabbedPane found = views(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    void aStackIsAnItemReadOnceOnTheClientWithoutControlsOrTools() throws Exception {
        SubjectIdentity pickaxe = new SubjectIdentity(SubjectIdentity.Kind.ITEM, "minecraft:iron_pickaxe", "Iron Pickaxe",
                "Minecraft", List.of(new SubjectIdentity.ClassLink("Class", "net.minecraft.world.item.PickaxeItem")),
                "minecraft:iron_pickaxe");
        InspectSubjectPayload stack = new InspectSubjectPayload("game-session",
                "stack 7", pickaxe, "", Map.of());
        withPanel(stack, target -> { }, panel -> {
            List<String> labels = labels(panel);
            assertTrue(labels.containsAll(List.of("Iron Pickaxe", "Item", "PickaxeItem")), labels::toString);
            assertTrue(panel.session().snapshot());
            assertFalse(panel.session().readsTools());
            assertFalse(accessibleNames(panel).contains("Tools"), "stacks run no tools");
            assertFalse(accessibleNames(panel).contains("Read Again"), "a kept stack reads the same again");
            assertFalse(labels.contains("Server"), "a kept stack is read on the client");
        });
    }

    @Test
    void aToolsSectionsFollowTheBuiltInOnesAndItsStatusOnlyShowsWhenThereIsSomethingToSay() {
        InspectionTool tool = new InspectionTool(Path.of("tools", "FurnaceTool.tdscript"), "FurnaceTool", "",
                List.of("minecraft:furnace"));
        FactSection burn = new FactSection("Burning", List.of(Fact.text("Lit", "true")), 1);
        String failure = "boom" + System.lineSeparator() + "at Tool.run";

        List<FactsPanel.Part> finished = ToolsMenu.parts(List.of(
                new InspectionSession.ToolRead(tool, false, List.of(burn), "", "")), "");
        List<FactsPanel.Part> failed = ToolsMenu.parts(List.of(
                new InspectionSession.ToolRead(tool, false, List.of(), "", failure)), "");

        FactsPanel.Origin origin = new FactsPanel.Origin("FurnaceTool", tool.path());
        assertEquals(List.of(new FactsPanel.Part(burn, origin)), finished);
        assertEquals("FurnaceTool › Burning", finished.getFirst().key());
        assertEquals(List.of(new FactsPanel.Part(new FactSection("FurnaceTool",
                List.of(Fact.problem("Status", failure)), 1), origin)), failed);
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

    private static void withPanel(Consumer<SubjectPanel> test) throws Exception {
        withPanel(target -> { }, test);
    }

    private static void withPanel(Consumer<NavigationTarget> navigator, Consumer<SubjectPanel> test) throws Exception {
        withPanel(SUBJECT, navigator, test);
    }

    private static void withPanel(InspectSubjectPayload subject, Consumer<NavigationTarget> navigator,
                                  Consumer<SubjectPanel> test) throws Exception {
        PackCatalogService catalog = new PackCatalogService(new InstancePaths(directory));
        try (ItemIconService icons = new ItemIconService()) {
            Throwable[] failure = new Throwable[1];
            SwingUtilities.invokeAndWait(() -> {
                SubjectPanel panel = SubjectPanel.occurrence(subject,
                        () -> { throw new IllegalStateException("Tests run no snippets"); },
                        () -> { throw new IllegalStateException("Tests have no project"); },
                        new DefinitionDetails.Services(catalog, RuntimeSourceCatalog::empty, icons, navigator));
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

    private static List<LinkLabel> links(Container container) {
        List<LinkLabel> links = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof LinkLabel link) links.add(link);
            if (child instanceof Container nested) links.addAll(links(nested));
        }
        return links;
    }

    private static List<String> accessibleNames(Container container) {
        List<String> names = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JComponent component && component.getAccessibleContext().getAccessibleName() != null) {
                names.add(component.getAccessibleContext().getAccessibleName());
            }
            if (child instanceof Container nested) names.addAll(accessibleNames(nested));
        }
        return names;
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
