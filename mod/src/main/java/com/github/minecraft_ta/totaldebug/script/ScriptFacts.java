package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structured information a script reports alongside its result. Companion shows each section with a presentation
 * fitting its facts: rows of text, bars, item slots and fluid tanks. Sections and facts beyond the transport limits
 * are counted but not kept.
 */
public final class ScriptFacts {
    private final Map<String, Section> sections = new LinkedHashMap<>();

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

    List<FactSection> snapshot() {
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

        private Section(String title, boolean retained) {
            this.title = title;
            this.retained = retained;
        }

        public Section text(String label, Object value) {
            return add(Fact.text(Fact.clip(label), Fact.clip(String.valueOf(value))));
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

        private Section add(Fact fact) {
            this.total++;
            if (this.retained && this.facts.size() < FactSection.MAX_FACTS) {
                this.facts.add(fact);
            }
            return this;
        }
    }
}
