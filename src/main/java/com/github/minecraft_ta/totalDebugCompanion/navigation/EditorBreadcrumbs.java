package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totalDebugCompanion.model.EditorLocation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Builds typed, display-ready breadcrumbs from one semantic editor destination. */
public final class EditorBreadcrumbs {
    private static final int MAX_VISIBLE_SEGMENTS = 11;

    private EditorBreadcrumbs() {
    }

    public static List<BreadcrumbSegment> create(
            EditorLocation location,
            NavigationTarget sourceTarget,
            JavaBreadcrumbResolver.Member member
    ) {
        Objects.requireNonNull(location, "location");
        if (sourceTarget == null) {
            return plain(location);
        }

        List<BreadcrumbSegment> segments = switch (sourceTarget) {
            case NavigationTarget.RuntimeClass runtimeClass -> runtime(location, runtimeClass);
            case NavigationTarget.LocalFile localFile -> local(location, localFile);
            case NavigationTarget.ArchiveEntry archiveEntry -> archive(location, archiveEntry);
            default -> plain(location);
        };
        if (member != null) {
            segments.add(new BreadcrumbSegment(member.label(), member.target(), location.tooltip()));
        }
        return collapse(segments);
    }

    private static List<BreadcrumbSegment> runtime(
            EditorLocation location,
            NavigationTarget.RuntimeClass runtimeClass
    ) {
        List<BreadcrumbSegment> result = new ArrayList<>();
        if (!location.module().isBlank()) {
            NavigationTarget moduleTarget = location.moduleId().isBlank()
                    ? null
                    : new NavigationTarget.ModuleSearch(Set.of(location.moduleId()), "");
            result.add(new BreadcrumbSegment(location.module(), moduleTarget, location.tooltip()));
        }

        List<String> path = location.path();
        List<String> classPath = classPath(runtimeClass.binaryName());
        int classPathStart = suffixStart(path, classPath);
        for (int index = 0; index < path.size(); index++) {
            String label = path.get(index);
            NavigationTarget target = null;
            int classIndex = index - classPathStart;
            if (classPathStart >= 0 && classIndex >= 0) {
                if (classIndex == classPath.size() - 1) {
                    target = runtimeClass;
                } else {
                    String packageName = String.join(".", classPath.subList(0, classIndex + 1));
                    target = new NavigationTarget.RuntimePackage(packageName, runtimeClass.binaryName());
                }
            }
            result.add(new BreadcrumbSegment(label, target, location.tooltip()));
        }
        return result;
    }

    private static List<BreadcrumbSegment> local(
            EditorLocation location,
            NavigationTarget.LocalFile localFile
    ) {
        List<BreadcrumbSegment> result = new ArrayList<>();
        Path base = localFile.path();
        for (int ignored = 0; ignored < location.path().size(); ignored++) {
            base = base.getParent();
            if (base == null) {
                return plain(location);
            }
        }
        if (!location.module().isBlank()) {
            result.add(new BreadcrumbSegment(
                    location.module(),
                    new NavigationTarget.LocalDirectory(base),
                    base.toString()
            ));
        }
        Path current = base;
        for (int index = 0; index < location.path().size(); index++) {
            current = current.resolve(location.path().get(index));
            boolean file = index == location.path().size() - 1;
            result.add(new BreadcrumbSegment(
                    location.path().get(index),
                    file ? localFile : new NavigationTarget.LocalDirectory(current),
                    current.toString()
            ));
        }
        return result;
    }

    private static List<BreadcrumbSegment> archive(
            EditorLocation location,
            NavigationTarget.ArchiveEntry archiveEntry
    ) {
        List<BreadcrumbSegment> result = new ArrayList<>();
        if (!location.module().isBlank()) {
            result.add(new BreadcrumbSegment(
                    location.module(),
                    new NavigationTarget.ArchiveDirectory(archiveEntry.archive(), ""),
                    archiveEntry.archive().toString()
            ));
        }
        StringBuilder entry = new StringBuilder();
        for (int index = 0; index < location.path().size(); index++) {
            if (!entry.isEmpty()) {
                entry.append('/');
            }
            entry.append(location.path().get(index));
            boolean file = index == location.path().size() - 1;
            result.add(new BreadcrumbSegment(
                    location.path().get(index),
                    file
                            ? archiveEntry
                            : new NavigationTarget.ArchiveDirectory(archiveEntry.archive(), entry.toString()),
                    archiveEntry.archive() + "!/" + entry
            ));
        }
        return result;
    }

    private static List<BreadcrumbSegment> plain(EditorLocation location) {
        List<BreadcrumbSegment> result = new ArrayList<>();
        if (!location.module().isBlank()) {
            result.add(new BreadcrumbSegment(location.module(), null, location.tooltip()));
        }
        location.path().forEach(segment -> result.add(new BreadcrumbSegment(segment, null, location.tooltip())));
        return result;
    }

    private static List<String> classPath(String binaryName) {
        List<String> result = new ArrayList<>(List.of(binaryName.split("\\.")));
        result.set(result.size() - 1, result.getLast() + ".java");
        return result;
    }

    private static int suffixStart(List<String> path, List<String> suffix) {
        int start = path.size() - suffix.size();
        return start >= 0 && path.subList(start, path.size()).equals(suffix) ? start : -1;
    }

    private static List<BreadcrumbSegment> collapse(List<BreadcrumbSegment> segments) {
        if (segments.size() <= MAX_VISIBLE_SEGMENTS) {
            return segments;
        }
        List<BreadcrumbSegment> visible = new ArrayList<>();
        visible.add(segments.getFirst());
        visible.addAll(segments.subList(1, 4));
        visible.add(new BreadcrumbSegment("…", null, ""));
        visible.addAll(segments.subList(segments.size() - 5, segments.size()));
        return visible;
    }
}
