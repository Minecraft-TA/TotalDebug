package com.github.minecraft_ta.totaldebug.client.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClassNameCatalogTest {
    private final ClassNameCatalog catalog = new ClassNameCatalog(List.of(
            "com.example.Outer$Inner",
            "net.minecraft.client.Minecraft",
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.level.block.Block",
            "net.minecraft.world.level.block.Blocks"
    ));

    @Test
    void suggestsOnePackageOrClassLevelAtATime() {
        assertEquals(List.of(), this.catalog.suggest(""));
        assertEquals(List.of("net"), this.catalog.suggest("n"));
        assertEquals(List.of("net.minecraft.client", "net.minecraft.world"), this.catalog.suggest("net.minecraft."));
        assertEquals(List.of("net.minecraft.world.level"), this.catalog.suggest("net.minecraft.world.l"));
        assertEquals(
                List.of("net.minecraft.world.level.block.Block", "net.minecraft.world.level.block.Blocks"),
                this.catalog.suggest("net.minecraft.world.level.block.b")
        );
    }
}
