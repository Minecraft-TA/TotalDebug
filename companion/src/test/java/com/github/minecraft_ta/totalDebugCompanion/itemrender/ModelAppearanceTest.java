package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelAppearanceTest {
    @TempDir Path pack;

    @Test
    void followsBlockstateModelsParentsAndTextureVariables() throws Exception {
        write("assets/testmod/blockstates/widget_block.json", """
                {"variants": {"facing=north": {"model": "testmod:block/widget_block"},
                              "facing=south": [{"model": "testmod:block/widget_block"}]}}
                """);
        write("assets/testmod/models/block/widget_block.json", """
                {"parent": "testmod:block/cube", "textures": {"side": "testmod:block/widget_side", "top": "#side"}}
                """);
        write("assets/testmod/models/block/cube.json", """
                {"textures": {"particle": "#top", "side": "testmod:block/ignored"}}
                """);
        write("assets/testmod/models/item/widget_block.json", """
                {"parent": "testmod:block/widget_block", "loader": "testmod:glow"}
                """);
        texture("assets/testmod/textures/block/widget_side.png", 16, 64);

        try (ItemRenderBackend backend = ItemRenderBackend.open(this.pack)) {
            ModelAppearance appearance = backend.appearance("testmod:widget_block", "testmod:item/widget_block");

            assertEquals(List.of("Blockstate", "Block model", "Item model"),
                    appearance.files().stream().map(ModelAppearance.File::role).toList());
            assertEquals(1, appearance.textures().size());
            ModelAppearance.Texture side = appearance.textures().getFirst();
            assertEquals("testmod:block/widget_side", side.id());
            assertEquals(List.of("side", "top", "particle"), side.variables());
            assertEquals(16, side.image().getHeight(), "An animation strip shows its first frame");
            assertEquals(List.of("testmod:glow"), appearance.loaders());
        }
    }

    @Test
    void unresolvedVariablesAreSkipped() {
        assertNull(ModelAppearance.resolveVariable("#missing", Map.of()));
        assertNull(ModelAppearance.resolveVariable("#a", Map.of("a", "#b", "b", "#a")));
        assertEquals("minecraft:block/stone", ModelAppearance.resolveVariable("#a", Map.of("a", "minecraft:block/stone")));
    }

    private void write(String path, String content) throws Exception {
        Path file = this.pack.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void texture(String path, int width, int height) throws Exception {
        Path file = this.pack.resolve(path);
        Files.createDirectories(file.getParent());
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", file.toFile());
    }
}
