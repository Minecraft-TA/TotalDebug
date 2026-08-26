package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceUsage;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeInventory;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.RuntimeModulePresentation;
import com.github.tth05.jindex.ReferenceKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

final class UsageTreeModel {
    enum GroupKind {
        SCOPE,
        USAGE_TYPE,
        MODULE,
        PACKAGE,
        CLASS
    }

    record Options(boolean module, boolean packageName, boolean fileStructure, boolean usageType) {
        static Options defaults() {
            return new Options(true, false, true, false);
        }
    }

    record Group(
            GroupKind kind,
            String label,
            int siteCount,
            long referenceCount,
            List<Group> children,
            List<ReferenceUsage> usages
    ) {
        Group {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(label, "label");
            children = List.copyOf(children);
            usages = List.copyOf(usages);
        }
    }

    private UsageTreeModel() {
    }

    static Group build(
            List<ReferenceUsage> usages,
            RuntimeSourceCatalog sourceCatalog,
            Options options
    ) {
        Objects.requireNonNull(sourceCatalog, "sourceCatalog");
        Objects.requireNonNull(options, "options");
        List<ReferenceUsage> ordered = new ArrayList<>(List.copyOf(usages));
        ordered.sort(Comparator
                .comparing((ReferenceUsage usage) -> pathKey(usage, sourceCatalog, options))
                .thenComparing(Comparator.naturalOrder()));

        MutableGroup root = new MutableGroup(new GroupKey(GroupKind.SCOPE, "runtime", "Usages in runtime"));
        for (ReferenceUsage usage : ordered) {
            root.add(path(usage, sourceCatalog, options), 0, usage);
        }
        return root.freeze();
    }

    private static List<GroupKey> path(
            ReferenceUsage usage,
            RuntimeSourceCatalog sourceCatalog,
            Options options
    ) {
        List<GroupKey> path = new ArrayList<>(4);
        if (options.usageType()) {
            path.add(new GroupKey(
                    GroupKind.USAGE_TYPE,
                    relationKey(usage.kinds()),
                    relationGroupLabel(usage.kinds())
            ));
        }
        if (options.module()) {
            RuntimeInventory.RuntimeModule module = sourceCatalog.moduleFor(usage.sourceId());
            path.add(new GroupKey(
                    GroupKind.MODULE,
                    module.id(),
                    RuntimeModulePresentation.of(module).label()
            ));
        }
        ReferenceLocation location = usage.location();
        if (options.packageName()) {
            String packageName = packageName(location.className());
            path.add(new GroupKey(
                    GroupKind.PACKAGE,
                    packageName,
                    packageName.isEmpty() ? "<default package>" : packageName
            ));
        }
        if (options.fileStructure()) {
            String packageName = packageName(location.className());
            String label = simpleClassName(location.className());
            if (!options.packageName() && !packageName.isEmpty()) {
                label += "  " + packageName;
            }
            path.add(new GroupKey(GroupKind.CLASS, location.className(), label));
        }
        return List.copyOf(path);
    }

    private static String pathKey(
            ReferenceUsage usage,
            RuntimeSourceCatalog sourceCatalog,
            Options options
    ) {
        return path(usage, sourceCatalog, options).stream()
                .map(group -> group.kind().ordinal() + "\u0000" + group.key())
                .collect(Collectors.joining("\u0001"));
    }

    static String relationLabel(ReferenceKind kind) {
        return switch (kind) {
            case CLASS_HIERARCHY -> "hierarchy";
            case CLASS_DECLARATION -> "type declaration";
            case CLASS_ANNOTATION_OR_METADATA -> "annotation/metadata";
            case CLASS_RUNTIME_TYPE -> "runtime type";
            case CLASS_MEMBER_USAGE -> "member usage";
            case FIELD_READ -> "read";
            case FIELD_WRITE -> "write";
            case FIELD_HANDLE -> "field handle";
            case METHOD_INVOKE -> "call";
            case METHOD_HANDLE -> "method handle";
            case STRING_LITERAL -> "string literal";
        };
    }

    private static String relationKey(Set<ReferenceKind> kinds) {
        return kinds.stream().sorted().map(Enum::name).collect(Collectors.joining("+"));
    }

    private static String relationGroupLabel(Set<ReferenceKind> kinds) {
        return kinds.stream()
                .sorted()
                .map(UsageTreeModel::relationLabel)
                .map(UsageTreeModel::capitalize)
                .collect(Collectors.joining(" + "));
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String packageName(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? "" : className.substring(0, separator);
    }

    static String simpleClassName(String className) {
        int separator = className.lastIndexOf('.');
        return className.substring(separator + 1).replace('$', '.');
    }

    private record GroupKey(GroupKind kind, String key, String label) {
        private GroupKey {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(label, "label");
        }
    }

    private static final class MutableGroup {
        private final GroupKey key;
        private final Map<GroupKey, MutableGroup> children = new LinkedHashMap<>();
        private final List<ReferenceUsage> usages = new ArrayList<>();
        private int siteCount;
        private long referenceCount;

        private MutableGroup(GroupKey key) {
            this.key = key;
        }

        private void add(List<GroupKey> path, int index, ReferenceUsage usage) {
            this.siteCount++;
            this.referenceCount += usage.occurrenceCount();
            if (index == path.size()) {
                this.usages.add(usage);
                return;
            }
            GroupKey childKey = path.get(index);
            this.children.computeIfAbsent(childKey, MutableGroup::new).add(path, index + 1, usage);
        }

        private Group freeze() {
            return new Group(
                    this.key.kind(),
                    this.key.label(),
                    this.siteCount,
                    this.referenceCount,
                    this.children.values().stream().map(MutableGroup::freeze).toList(),
                    this.usages
            );
        }
    }
}
