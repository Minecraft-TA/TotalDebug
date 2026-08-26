package com.github.minecraft_ta.totalDebugCompanion.ui.presentation;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeInventory;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One UI-owned representation of runtime module names, ids, and source provenance. */
public record RuntimeModulePresentation(
        String primaryName,
        String moduleId,
        String tooltip
) {
    public RuntimeModulePresentation {
        if (Objects.requireNonNull(primaryName, "primaryName").isBlank()) {
            throw new IllegalArgumentException("A module display name must not be blank");
        }
        if (Objects.requireNonNull(moduleId, "moduleId").isBlank()) {
            throw new IllegalArgumentException("A module id must not be blank");
        }
        tooltip = Objects.requireNonNullElse(tooltip, "");
    }

    public static RuntimeModulePresentation of(RuntimeInventory.RuntimeModule module) {
        RuntimeInventory.RuntimeModule value = Objects.requireNonNull(module, "module");
        return new RuntimeModulePresentation(value.displayName(), value.id(), fullLabel(value));
    }

    public static RuntimeModulePresentation of(RuntimeSnapshotBytecodeSource.Source source) {
        RuntimeSnapshotBytecodeSource.Source value = Objects.requireNonNull(source, "source");
        RuntimeInventory.RuntimeModule module = value.module();
        return new RuntimeModulePresentation(
                module.displayName(),
                module.id(),
                fullLabel(module) + " | " + value.logicalUri()
        );
    }

    public PrimarySecondaryText text() {
        return new PrimarySecondaryText(
                this.primaryName,
                this.primaryName.equals(this.moduleId) ? "" : this.moduleId
        );
    }

    public String label() {
        return this.primaryName.equals(this.moduleId)
                ? this.primaryName
                : this.primaryName + " (" + this.moduleId + ')';
    }

    public static String compactSummary(Collection<RuntimeInventory.RuntimeModule> modules) {
        List<RuntimeInventory.RuntimeModule> sortedModules = modules.stream()
                .distinct()
                .sorted(Comparator.comparing(RuntimeInventory.RuntimeModule::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(RuntimeInventory.RuntimeModule::id))
                .toList();
        Map<String, Integer> displayNameCounts = new HashMap<>();
        sortedModules.forEach(module -> displayNameCounts.merge(
                module.displayName().toLowerCase(Locale.ROOT),
                1,
                Integer::sum
        ));
        List<String> labels = sortedModules.stream()
                .map(module -> displayNameCounts.get(module.displayName().toLowerCase(Locale.ROOT)) > 1
                        ? RuntimeModulePresentation.of(module).label()
                        : module.displayName())
                .toList();
        if (labels.size() <= 2) {
            return String.join(", ", labels);
        }
        return labels.get(0) + ", " + labels.get(1) + " +" + (labels.size() - 2);
    }

    private static String fullLabel(RuntimeInventory.RuntimeModule module) {
        return module.displayName().equals(module.id())
                ? module.displayName()
                : module.displayName() + " (module id: " + module.id() + ')';
    }
}
