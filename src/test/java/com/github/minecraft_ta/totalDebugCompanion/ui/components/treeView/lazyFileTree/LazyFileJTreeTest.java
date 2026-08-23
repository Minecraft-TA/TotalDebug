package com.github.minecraft_ta.totalDebugCompanion.ui.components.treeView.lazyFileTree;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LazyFileJTreeTest {

    @Test
    void sortsDirectoriesFirstAndNamesWithoutCaseSurprises() {
        TreeItem file = new TreeItem("zeta.txt");
        DirectoryTreeItem directory = new DirectoryTreeItem("Zulu") {
            @Override
            public List<TreeItem> loadChildren() {
                return List.of();
            }
        };

        assertTrue(LazyFileJTree.compareTreeItems(directory, file) < 0);
        assertTrue(LazyFileJTree.compareTreeItems(new TreeItem("Alpha.txt"), new TreeItem("beta.txt")) < 0);
    }
}
