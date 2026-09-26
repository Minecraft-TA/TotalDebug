package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactData;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import net.minecraft.core.registries.BuiltInRegistries;
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
 * fitting its facts: rows of text, bars, item slots, fluid tanks and exact NBT data. Sections and facts beyond the
 * transport limits are counted but not kept.
 */
public final class ScriptFacts {
    private final Map<String, Section> sections = new LinkedHashMap<>();
    private final Consumer<String> log;
    private long dataBudget = FactData.MAX_TOTAL_BYTES;

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
            section(sectionTitle).failed(failure);
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

    public final class Section {
        private final String title;
        private final boolean retained;
        private final List<Fact> facts = new ArrayList<>();
        private int total;

        private Section(String title, boolean retained) {
            this.title = title;
            this.retained = retained;
        }

        public Section text(String label, Object value) {
            add(Fact.text(Fact.clip(label), Fact.clip(String.valueOf(value))));
            return this;
        }

        /** Reports that reading {@code label} failed; facts already reported stay. */
        public Section problem(String label, Throwable failure) {
            add(problemFact(label, failure));
            return this;
        }

        /** Reports that the section's read failed; a full section gives up its last fact, since the failure matters more. */
        void failed(Throwable failure) {
            Fact problem = problemFact("Read failed", failure);
            if (this.retained && this.facts.size() >= FactSection.MAX_FACTS) {
                this.total++;
                this.facts.set(this.facts.size() - 1, problem);
            } else {
                add(problem);
            }
        }

        private static Fact problemFact(String label, Throwable failure) {
            String message = failure.getMessage();
            String summary = failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
            return Fact.problem(Fact.clip(label), Fact.clip(summary));
        }

        /** Reports a class by its simple name; Companion opens its source when the value is clicked. */
        public Section classLink(String label, Class<?> type) {
            return classLink(label, type, "");
        }

        /** Reports a class by its simple name followed by {@code detail}, such as where it applies, unless empty. */
        public Section classLink(String label, Class<?> type, String detail) {
            String name = type.getName();
            String simple = type.getSimpleName().isEmpty() ? name.substring(name.lastIndexOf('.') + 1)
                    : type.getSimpleName();
            String value = detail.isEmpty() ? simple : simple + ", " + detail;
            add(Fact.text(Fact.clip(label), Fact.clip(value)).withLink(FactLink.toClass(name)));
            return this;
        }

        public Section bar(String label, long amount, long capacity, String unit) {
            add(Fact.bar(Fact.clip(label), Math.max(0, amount), Math.max(0, capacity), Fact.clip(unit)));
            return this;
        }

        /** Reports a slot's contents; an empty stack is shown as an empty slot. */
        public Section stack(String label, ItemStack stack) {
            add(stackFact(label, stack));
            return this;
        }

        /**
         * Reports a slot as automation reaches it through the side named {@code label}, such as {@code Top}: whether it
         * takes more of what it holds and whether it gives some, either null when the slot's state cannot tell.
         * Consecutive slots with the same label are shown as one row.
         */
        public Section slot(String label, ItemStack stack, Boolean takes, Boolean gives) {
            add(stackFact(label, stack).withTransfer(new Fact.Transfer(takes, gives)));
            return this;
        }

        private static Fact stackFact(String label, ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return Fact.stack(Fact.clip(label), "", 0, "");
            }
            return Fact.stack(
                    Fact.clip(label),
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(),
                    Fact.clip(stack.getHoverName().getString())
            );
        }

        /** Reports a tank's contents and capacity in millibuckets. */
        public Section fluid(String label, FluidStack stack, long capacity) {
            if (stack == null || stack.isEmpty()) {
                add(Fact.fluid(Fact.clip(label), "", 0, Math.max(0, capacity), ""));
                return this;
            }
            add(Fact.fluid(
                    Fact.clip(label),
                    BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString(),
                    stack.getAmount(),
                    Math.max(0, capacity),
                    Fact.clip(stack.getHoverName().getString())
            ));
            return this;
        }

        /**
         * Reports a tag as exact data, which Companion can show as a tree, search and copy as SNBT. A tag larger than
         * the transport budget keeps its first entries in printing order and records which parts were left out.
         */
        public Section nbt(String label, Tag tag) {
            if (tag == null) {
                return text(label, "null");
            }
            int budget = (int) Math.min(FactData.MAX_BYTES, ScriptFacts.this.dataBudget);
            if (budget < NbtFactData.MINIMUM_BYTES) {
                return text(label, "Not reported: the data budget of this read is used up");
            }
            FactData data = NbtFactData.encode(tag, budget);
            if (add(Fact.data(Fact.clip(label), data))) {
                ScriptFacts.this.dataBudget -= data.size();
            }
            return this;
        }

        private boolean add(Fact fact) {
            this.total++;
            if (this.retained && this.facts.size() < FactSection.MAX_FACTS) {
                this.facts.add(fact);
                return true;
            }
            return false;
        }
    }
}
