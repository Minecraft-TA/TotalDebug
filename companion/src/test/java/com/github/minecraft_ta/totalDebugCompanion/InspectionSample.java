package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionText;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionValue;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactData;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;

import java.util.List;
import java.util.Map;

/** A furnace-like block as an inspection reads it: storage whose faces differ, energy, capabilities and NBT. */
final class InspectionSample {
    static final SubjectIdentity IDENTITY = new SubjectIdentity(SubjectIdentity.Kind.BLOCK, "testmod:widget_block",
            "block.testmod.widget_block", "Test Mod",
            List.of(new SubjectIdentity.ClassLink("Class", "com.example.testmod.WidgetBlock"),
                    new SubjectIdentity.ClassLink("Block entity class", "com.example.testmod.WidgetBlockEntity")),
            "");
    static final InspectSubjectPayload SUBJECT = new InspectSubjectPayload("ui-harness",
            "block minecraft:overworld 12 64 -3", IDENTITY, "", Map.of());

    private InspectionSample() {
    }

    /** {@code stack} as the side named {@code label} exposes it. */
    private static Fact side(String label, Fact stack, boolean takes, boolean gives) {
        return Fact.stack(label, stack.id(), stack.amount(), stack.value()).withTransfer(new Fact.Transfer(takes, gives));
    }

    static ExecutionResult read() {
        Fact coal = Fact.stack("Slot 0", "minecraft:coal", 8, "Coal");
        Fact ore = Fact.stack("Slot 1", "minecraft:iron_ore", 3, "Iron Ore");
        Fact output = Fact.stack("Slot 2", "minecraft:iron_ingot", 12, "Iron Ingot");
        List<FactSection> sections = List.of(
                new FactSection("Block state", List.of(Fact.text("facing", "north"), Fact.text("lit", "true")), 2),
                new FactSection("Items", List.of(
                        side("Without a side", coal, true, true), side("Without a side", ore, true, true),
                        side("Without a side", output, false, true),
                        side("Sides", coal, true, true),
                        side("Top", ore, true, true),
                        side("Bottom", output, false, true), side("Bottom", coal, true, false)), 7),
                new FactSection("Fluids", List.of(Fact.fluid("Tank 0", "minecraft:lava", 2_000, 4_000, "Lava"),
                        Fact.text("All sides", "Lava: takes and gives")), 2),
                new FactSection("Energy", List.of(Fact.bar("Stored", 12_400, 50_000, "FE"),
                        Fact.text("Accepts energy", "Yes"), Fact.text("Provides energy", "No")), 3),
                new FactSection("Capabilities", List.of(
                        Fact.text("neoforge:item_handler", "SidedInvWrapper")
                                .withLink(FactLink.toClass("net.neoforged.neoforge.items.wrapper.SidedInvWrapper")),
                        Fact.text("neoforge:energy", "EnergyStorage")
                                .withLink(FactLink.toClass("net.neoforged.neoforge.energy.EnergyStorage"))), 2),
                new FactSection("NBT", List.of(Fact.data("Block entity", FactData.of(
                        new byte[]{10, 2, 0, 8, 'B', 'u', 'r', 'n', 'T', 'i', 'm', 'e', 0, 42, 0}, List.of()))), 1)
        );
        ExecutionValue value = new ExecutionValue(
                ExecutionText.complete("com.github.minecraft_ta.totaldebug.script.ScriptTarget$PlacedBlock"),
                ExecutionText.complete("PlacedBlock[pos=12, 64, -3]"),
                ExecutionText.empty(),
                ExecutionValue.Kind.OBJECT,
                1,
                0,
                false,
                List.of()
        );
        return new ExecutionResult(ExecutionStatus.RUN_COMPLETED, ExecutionText.empty(), value, ExecutionText.empty())
                .withFacts(sections)
                .withIdentity(IDENTITY);
    }
}
