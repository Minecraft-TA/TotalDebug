package com.github.minecraft_ta.totalDebugCompanion.ui.presentation;

import com.github.minecraft_ta.totalDebugCompanion.ui.Tooltip;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;

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
        String location
) {
    public RuntimeModulePresentation {
        if (Objects.requireNonNull(primaryName, "primaryName").isBlank()) {
            throw new IllegalArgumentException("A module display name must not be blank");
        }
        if (Objects.requireNonNull(moduleId, "moduleId").isBlank()) {
            throw new IllegalArgumentException("A module id must not be blank");
        }
        location = Objects.requireNonNullElse(location, "");
    }

    public static RuntimeModulePresentation of(RuntimeInventory.RuntimeModule module) {
        RuntimeInventory.RuntimeModule value = Objects.requireNonNull(module, "module");
        return new RuntimeModulePresentation(value.displayName(), value.id(), "");
    }

    public static RuntimeModulePresentation of(RuntimeSnapshotBytecodeSource.Source source) {
        RuntimeSnapshotBytecodeSource.Source value = Objects.requireNonNull(source, "source");
        RuntimeInventory.RuntimeModule module = value.module();
        return new RuntimeModulePresentation(module.displayName(), module.id(), location(value.logicalUri()));
    }

    /** The module's name, its id when that differs, and the file its classes come from. */
    public String tooltip() {
        return describe(Tooltip.of("")).html();
    }

    /** Adds this module to a tooltip, for example one listing every module a search result appears in. */
    public Tooltip describe(Tooltip tooltip) {
        tooltip.line(this.primaryName);
        if (!this.primaryName.equals(this.moduleId)) tooltip.detail("Module " + this.moduleId);
        return tooltip.detail(this.location);
    }

    /**
     * Where classes come from, as short as it can be read: a JAR's file name, or for a JAR nested in another the
     * inner JAR and the one containing it.
     */
    public static String location(String logicalUri) {
        if (logicalUri == null || logicalUri.isBlank()) return "";
        String[] nesting = logicalUri.split("!/");
        String inner = fileName(nesting[nesting.length - 1]);
        if (nesting.length == 1) return inner;
        return inner + " in " + fileName(nesting[0]);
    }

    private static String fileName(String uriPart) {
        String path = uriPart.replaceFirst("%23\\d+$", "");
        String name = path.substring(path.lastIndexOf('/') + 1);
        return URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8);
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
}
