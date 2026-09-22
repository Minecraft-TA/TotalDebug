package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import javax.swing.Icon;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** One visible row owns every real directory descriptor represented by its label. */
public final class DirectoryChain extends DirectoryTreeItem {
    private final List<DirectoryTreeItem> segments;
    private final String separator;

    private DirectoryChain(List<DirectoryTreeItem> segments, String separator) {
        super(segments.getFirst().getName());
        this.segments = List.copyOf(segments);
        this.separator = separator;
        setPresentation(PrimarySecondaryText.primary(String.join(separator, segments.stream().map(TreeItem::getName).toList())));
    }

    public static List<? extends TreeItem> segments(TreeItem item) {
        return item instanceof DirectoryChain chain ? chain.segments : List.of(item);
    }

    public static TreeItem first(TreeItem item) { return segments(item).getFirst(); }
    public static TreeItem last(TreeItem item) { return segments(item).getLast(); }
    static boolean changedDuringDiscovery(TreeItem item) {
        return segments(item).stream().anyMatch(part -> part instanceof FileSystemDirectoryItem folder && folder.changedDuringDiscovery());
    }
    public String separator() { return separator; }
    public boolean supportsSegmentSelection() { return segments.getFirst() instanceof FileSystemDirectoryItem; }
    @Override public Icon getIcon() { return segments.getFirst().getIcon(); }
    @Override public int getSortPriority() { return segments.getFirst().getSortPriority(); }
    @Override public String getTooltip() { return segments.getLast().getTooltip(); }
    @Override public List<TreeItem> loadChildren() { return segments.getLast().loadChildren(); }
    @Override protected boolean isInitiallyEmpty() { return segments.getLast().isInitiallyEmpty(); }
    @Override public void dispose() { segments.forEach(TreeItem::dispose); }

    static List<TreeItem> compactChildren(List<TreeItem> children) {
        var result = new ArrayList<TreeItem>(children.size());
        try {
            for (TreeItem item : children) result.add(compact(item));
            return result;
        } catch (RuntimeException failure) {
            result.forEach(TreeItem::dispose);
            children.subList(result.size(), children.size()).forEach(TreeItem::dispose);
            throw failure;
        }
    }

    private static TreeItem compact(TreeItem item) {
        if (!(item instanceof DirectoryTreeItem directory) || directory.compactSeparator() == null) return item;
        var segments = new ArrayList<DirectoryTreeItem>();
        var visited = new HashSet<Object>();
        String separator = directory.compactSeparator();
        segments.add(directory);
        try { visited.add(directory.compactIdentity()); }
        catch (IOException unreadable) { return item; }
        try {
            while (true) {
                DirectoryTreeItem next;
                try { next = segments.getLast().singleDirectoryChild(); }
                catch (IOException | DirectoryIteratorException unreadable) { break; }
                if (next == null) break;
                boolean eligible;
                try { eligible = separator.equals(next.compactSeparator()) && visited.add(next.compactIdentity()); }
                catch (IOException unreadable) { next.dispose(); break; }
                if (!eligible) {
                    next.dispose();
                    break;
                }
                segments.add(next);
            }
            return segments.size() == 1 ? item : new DirectoryChain(segments, separator);
        } catch (RuntimeException failure) {
            segments.subList(1, segments.size()).forEach(TreeItem::dispose);
            throw failure;
        }
    }
}
