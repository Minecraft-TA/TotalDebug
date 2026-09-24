package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Structured information a script reports alongside its result. Companion shows each section with a presentation
 * fitting its facts: rows of text, bars, item slots and fluid tanks. Sections and facts beyond the transport limits
 * are counted but not kept.
 */
public final class ScriptFacts {
    private final Map<String, Section> sections = new LinkedHashMap<>();
    private final Consumer<String> log;

    /** {@code log} receives the stack traces of guarded reads that failed. */
    public ScriptFacts(Consumer<String> log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Runs one independent part of a read. When it fails, the facts it reported so far remain, the section gets a
     * problem naming the failure and the stack trace goes to the run's output; later parts still run.
     */
    public void guarded(String sectionTitle, Runnable read) {
        try {
            read.run();
        } catch (RuntimeException | LinkageError failure) {
            section(sectionTitle).problem("Read failed", failure);
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            this.log.accept(sectionTitle + " could not be read:" + System.lineSeparator() + trace);
        }
    }

    /** Returns the section with this title, creating it after the ones already reported. */
    public Section section(String title) {
        String key = Fact.clip(Objects.requireNonNull(title, "title"));
        Section existing = this.sections.get(key);
        if (existing != null) {
            return existing;
        }
        Section section = new Section(key, this.sections.size() < FactSection.MAX_SECTIONS);
        if (section.retained) {
            this.sections.put(key, section);
        }
        return section;
    }

    /** The sections reported so far, in reporting order. */
    public List<FactSection> snapshot() {
        List<FactSection> result = new ArrayList<>(this.sections.size());
        for (Section section : this.sections.values()) {
            result.add(new FactSection(section.title, section.facts, section.total));
        }
        return result;
    }

    public static final class Section {
        private final String title;
        private final boolean retained;
        private final List<Fact> facts = new ArrayList<>();
        private int total;
        private int nodes;

        private Section(String title, boolean retained) {
            this.title = title;
            this.retained = retained;
        }

        public Section text(String label, Object value) {
            return add(Fact.text(Fact.clip(label), Fact.clip(String.valueOf(value))));
        }

        /** Reports that reading {@code label} failed; facts already reported stay. */
        public Section problem(String label, Throwable failure) {
            String message = failure.getMessage();
            String summary = failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
            return add(Fact.problem(Fact.clip(label), Fact.clip(summary)));
        }

        public Section bar(String label, long amount, long capacity, String unit) {
            return add(Fact.bar(Fact.clip(label), Math.max(0, amount), Math.max(0, capacity), Fact.clip(unit)));
        }

        /** Reports a slot's contents; an empty stack is shown as an empty slot. */
        public Section stack(String label, ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return add(Fact.stack(Fact.clip(label), "", 0, ""));
            }
            return add(Fact.stack(
                    Fact.clip(label),
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(),
                    Fact.clip(stack.getHoverName().getString())
            ));
        }

        /** Reports a tank's contents and capacity in millibuckets. */
        public Section fluid(String label, FluidStack stack, long capacity) {
            if (stack == null || stack.isEmpty()) {
                return add(Fact.fluid(Fact.clip(label), "", 0, Math.max(0, capacity), ""));
            }
            return add(Fact.fluid(
                    Fact.clip(label),
                    BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString(),
                    stack.getAmount(),
                    Math.max(0, capacity),
                    Fact.clip(stack.getHoverName().getString())
            ));
        }

        /**
         * Reports a tag as a tree: compounds and lists become nodes, other tags show their SNBT text. Entries beyond
         * the section's node budget or depth are counted but not kept.
         */
        public Section nbt(String label, Tag tag) {
            if (tag == null) {
                return text(label, "null");
            }
            int[] budget = {FactSection.MAX_NODES - this.nodes - 1};
            return add(nbtFact(Fact.clip(label), tag, 1, budget));
        }

        private static Fact nbtFact(String label, Tag tag, int depth, int[] budget) {
            if (tag instanceof CompoundTag compound) {
                List<String> keys = new ArrayList<>(compound.getAllKeys());
                keys.sort(null);
                List<Fact> children = new ArrayList<>();
                for (String key : keys) {
                    if (depth >= FactSection.MAX_DEPTH || budget[0] <= 0) break;
                    budget[0]--;
                    children.add(nbtFact(Fact.clip(key), compound.get(key), depth + 1, budget));
                }
                return Fact.tree(label, count(keys.size(), "entry", "entries"), children, keys.size());
            }
            if (tag instanceof ListTag list) {
                List<Fact> children = new ArrayList<>();
                for (int index = 0; index < list.size(); index++) {
                    if (depth >= FactSection.MAX_DEPTH || budget[0] <= 0) break;
                    budget[0]--;
                    children.add(nbtFact("[" + index + "]", list.get(index), depth + 1, budget));
                }
                return Fact.tree(label, count(list.size(), "item", "items"), children, list.size());
            }
            if (tag instanceof StringTag string) {
                return Fact.text(label, Fact.clip("\"" + string.getAsString() + "\""));
            }
            return Fact.text(label, Fact.clip(tag.toString()));
        }

        private static String count(int size, String singular, String plural) {
            return size + " " + (size == 1 ? singular : plural);
        }

        private Section add(Fact fact) {
            this.total++;
            int nodes = fact.nodeCount();
            if (this.retained && this.facts.size() < FactSection.MAX_FACTS
                    && this.nodes + nodes <= FactSection.MAX_NODES) {
                this.facts.add(fact);
                this.nodes += nodes;
            }
            return this;
        }
    }
}
