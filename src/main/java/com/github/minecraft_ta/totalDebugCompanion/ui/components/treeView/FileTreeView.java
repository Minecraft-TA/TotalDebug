package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.messages.codeView.DecompileOrOpenMessage;
import com.github.minecraft_ta.totalDebugCompanion.model.BaseScriptView;
import com.github.minecraft_ta.totalDebugCompanion.model.CodeView;
import com.github.minecraft_ta.totalDebugCompanion.model.ScriptView;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.EditorTabs;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree.*;
import com.github.minecraft_ta.totalDebugCompanion.util.TextUtils;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

public class FileTreeView extends JScrollPane {

    private static final Path MODS_PATH = CompanionApp.getWorkspaceDirectory().resolve("mods");

    public FileTreeView(EditorTabs tabs) {
        super();

        var tree = new LazyFileJTree() {
            @Override
            protected void showPopupMenu(LazyTreeNode node, TreeItem treeItem, int x, int y) {
                if (!(treeItem instanceof FileSystemFileItem))
                    return;

                var popupMenu = new JPopupMenu();
                var deleteItem = popupMenu.add("Delete");
                deleteItem.setIcon(Icons.DELETE);
                deleteItem.addActionListener(event -> deleteSelectedItems());

                popupMenu.show(this, x, y);
            }
        };

        tree.addMouseDoubleClickListener((node, item) -> {
            if (item.isDirectory())
                return;

            if (item instanceof FileSystemFileItem fileItem) {
                if (node.getParent().getUserObject().getName().equals("scripts")
                        && CompanionApp.hasCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION)) {
                    var name = fileItem.getName().replace(".java", "");

                    tabs.focusOrCreateIfAbsent(ScriptView.class, sv -> sv.getTitle().equals(name + ".java"), () -> {
                        if (name.equals("BaseScript"))
                            return new BaseScriptView(name);
                        else
                            return new ScriptView(name);
                    });
                } else {
                    tabs.focusOrCreateIfAbsent(CodeView.class, cv -> cv.getPath().equals(fileItem.getPath()), () -> new CodeView(fileItem.getPath(), 0));
                }
            } else if (item instanceof ZipFileRootItem.Entry) {
                if (!item.getName().endsWith(".class"))
                    return;

                //Reconstruct the class name
                StringBuilder fullName = new StringBuilder(item.getName().substring(0, item.getName().length() - 6));
                while (!((node = node.getParent()).getUserObject() instanceof ZipFileRootItem)) {
                    fullName.insert(0, '.').insert(0, node.getUserObject().getName());
                }
                CompanionApp.SERVER.getMessageProcessor().enqueueMessage(new DecompileOrOpenMessage(fullName.toString()));
            }
        });

        tree.setItemFactory(new FileTreeItemFactory() {
            @Override
            public FileSystemFileItem createFileSystemFileItem(Path path) {
                var item = super.createFileSystemFileItem(path);

                var fileName = item.getName();
                var splitIndex = fileName.lastIndexOf('.', fileName.length() - ".java".length() - 1);
                if (splitIndex != -1)
                    item.setRenderedName(TextUtils.htmlPrimarySecondaryString(fileName.substring(splitIndex + 1), "  ", fileName.substring(0, splitIndex)));

                if (path.getParent().getFileName().toString().equals("scripts"))
                    item.setIcon(Icons.JAVA_FILE);
                else
                    item.setIcon(Icons.JAVA_CLASS);
                return item;
            }
        });

        List<DirectoryTreeItem> rootItems = new ArrayList<>();
        if (CompanionApp.hasCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION)) {
            rootItems.add(tree.getItemFactory().createFileSystemDirectoryItem(
                    CompanionApp.getRootPath().resolve("scripts"),
                    true
            ));
        }
        rootItems.add(tree.getItemFactory().createFileSystemDirectoryItem(
                CompanionApp.getRootPath().resolve("decompiled-files"),
                true
        ));
        rootItems.add(new DirectoryTreeItem("mods") {
                    @Override
                    public List<TreeItem> loadChildren() {
                        try {
                            return Files.walk(MODS_PATH, 1)
                                    .skip(1)
                                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                                    .<TreeItem>map(ZipFileRootItem::new)
                                    .toList();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
        tree.addRootNodes(rootItems.toArray(DirectoryTreeItem[]::new));

        setViewportView(tree);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 3));
    }
}
