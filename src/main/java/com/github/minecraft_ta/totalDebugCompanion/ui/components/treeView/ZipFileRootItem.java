package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.DirectoryTreeItem;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.TreeItem;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import javax.swing.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ZipFileRootItem extends DirectoryTreeItem {

    private final Path path;
    private Node root;

    public ZipFileRootItem(Path path) {
        super(path.getFileName().toString());
        this.path = path;
    }

    @Override
    public List<TreeItem> loadChildren() {
        if (this.root == null) {
            this.root = new Node("");
            indexZipFile(path);
        }

        return new DirectoryEntry(this.root, "", false).loadChildren();
    }

    @Override
    public Icon getIcon() {
        return Icons.JAR_FILE;
    }

    @Override
    public String getTooltip() {
        return this.path.toString();
    }

    private void indexZipFile(Path path) {
        try (ZipFile file = new ZipFile(path.toFile())) {
            var enumeration = file.getEntries();
            ZipArchiveEntry el;
            while (enumeration.hasMoreElements()) {
                el = enumeration.nextElement();

                var name = el.getName();
                if (name.endsWith("/"))
                    name = name.substring(0, name.length() - 1);
                var index = name.lastIndexOf('/');
                root.add(index == -1 ? "" : name.substring(0, index), new Node(index == -1 ? name : name.substring(index + 1)));
            }
        } catch (Throwable e) {
            e.printStackTrace();
            throw new IllegalArgumentException();
        }
    }

    public static final class DirectoryEntry extends DirectoryTreeItem {

        private final Node node;
        private final String entryPath;
        private final boolean resourceTree;

        private DirectoryEntry(Node node, String entryPath, boolean resourceTree) {
            super(node.name);
            this.node = node;
            this.entryPath = entryPath;
            this.resourceTree = resourceTree;
        }

        @Override
        public List<TreeItem> loadChildren() {
            return node.getChildren().stream().map(n -> {
                String childPath = this.entryPath.isEmpty() ? n.name : this.entryPath + '/' + n.name;
                boolean childResourceTree = this.resourceTree
                        || n.name.equalsIgnoreCase("assets")
                        || n.name.equalsIgnoreCase("data")
                        || n.name.equalsIgnoreCase("META-INF");
                if (n.getChildren().isEmpty()) {
                    return new Entry(n, childPath);
                }
                return new DirectoryEntry(n, childPath, childResourceTree);
            }).toList();
        }

        @Override
        public Icon getIcon() {
            return FileTreeIcons.forArchiveDirectory(
                    this.node.name,
                    this.resourceTree,
                    this.node.hasClassDescendant()
            );
        }

        @Override
        public String getTooltip() {
            return this.entryPath + '/';
        }
    }

    public static class Entry extends TreeItem {

        private final String entryPath;

        private Entry(Node node, String entryPath) {
            super(node.name);
            this.entryPath = entryPath;
        }

        @Override
        public Icon getIcon() {
            return FileTreeIcons.forFileName(getName());
        }

        @Override
        public String getTooltip() {
            return this.entryPath;
        }
    }

    private static final class Node {

        private final String name;
        private List<Node> children;

        private Node(String name) {
            this.name = name;
        }

        private boolean add(String fullPath, Node node) {
            var isRoot = this.name.isEmpty();
            var index = fullPath.indexOf('/');
            var part1 = getFirstPart(fullPath);
            if (!this.name.equals(part1) && !isRoot)
                return false;
            if (index == -1 && (!isRoot || this.name.equals(part1))) {
                getChildren().add(node);
                return true;
            }

            var nextPath = isRoot ? fullPath : fullPath.substring(index + 1);
            if (getChildren().stream().noneMatch(c -> c.add(nextPath, node))) {
                var newName = getFirstPart(nextPath);
                var newNode = new Node(newName);
                newNode.add(nextPath, node);
                getChildren().add(newNode);
            }

            return true;
        }

        private List<Node> getChildren() {
            if (this.children == null)
                this.children = new ArrayList<>();
            return this.children;
        }

        private boolean hasClassDescendant() {
            return getChildren().stream().anyMatch(child ->
                    child.name.toLowerCase(java.util.Locale.ROOT).endsWith(".class")
                            || child.hasClassDescendant()
            );
        }

        private String getFirstPart(String path) {
            var index = path.indexOf('/');
            return index == -1 ? path : path.substring(0, index);
        }

        @Override
        public String toString() {
            return "Node{" +
                   "name='" + name + '\'' +
                   '}';
        }
    }
}
