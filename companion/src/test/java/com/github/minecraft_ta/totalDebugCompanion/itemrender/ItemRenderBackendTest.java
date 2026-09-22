package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemRenderBackendTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersGeneratedItemUsingHighestPriorityResourceAndRuntimeTint() throws Exception {
        Path basePack = this.temporaryDirectory.resolve("base-pack");
        writeGeneratedParent(basePack);
        writeText(basePack, "assets/test/models/item/gem.json", """
                {
                  "parent": "minecraft:item/generated",
                  "textures": { "layer0": "test:item/gem" }
                }
                """);
        writePng(basePack, "assets/test/textures/item/gem.png", solidImage(2, 2, 0xFFFF0000));

        Path overrideArchive = this.temporaryDirectory.resolve("override.zip");
        BufferedImage overrideTexture = solidImage(2, 2, 0xFFFFFFFF);
        overrideTexture.setRGB(0, 0, 0);
        writeArchive(overrideArchive, Map.of(
                "assets/test/textures/item/gem.png", pngBytes(overrideTexture)
        ));

        ItemRenderRequest request = new ItemRenderRequest(
                ItemModelId.parse("test:item/gem"),
                16,
                Map.of(0, 0xFF20C040)
        );
        try (ItemRenderBackend backend = ItemRenderBackend.open(basePack, overrideArchive)) {
            BufferedImage first = backend.render(request);
            assertEquals(0xFF20C040, first.getRGB(8, 8));
            assertEquals(0, first.getRGB(2, 2));

            first.setRGB(8, 8, 0);
            BufferedImage second = backend.render(request);
            assertNotSame(first, second);
            assertEquals(0xFF20C040, second.getRGB(8, 8));
        }
    }

    @Test
    void readsAResourcePackNestedInsideAnArchive() throws Exception {
        Path archive = this.temporaryDirectory.resolve("mod.jar");
        writeArchive(archive, Map.of(
                "compat_packs/example/assets/minecraft/models/item/generated.json",
                "{\"parent\":\"builtin/generated\",\"gui_light\":\"front\"}".getBytes(StandardCharsets.UTF_8),
                "compat_packs/example/assets/test/models/item/nested.json",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"test:item/nested\"}}"
                        .getBytes(StandardCharsets.UTF_8),
                "compat_packs/example/assets/test/textures/item/nested.png",
                pngBytes(solidImage(1, 1, 0xFF3070D0))
        ));

        try (ItemRenderBackend backend = ItemRenderBackend.openResourceRoots(List.of(
                ItemRenderResourceRoot.nested(archive, "compat_packs/example")
        ))) {
            assertEquals(
                    0xFF3070D0,
                    backend.render(ItemRenderRequest.of("test:item/nested", 8)).getRGB(4, 4)
            );
            assertTrue(backend.discoverItemModels().contains(ItemModelId.parse("test:item/nested")));
        }
    }

    @Test
    void resolvesBareFaceTextureMapKeysLikeMinecraft() throws Exception {
        Path pack = this.temporaryDirectory.resolve("bare-face-texture-pack");
        writeText(pack, "assets/test/models/item/bare.json", """
                {
                  "gui_light": "front",
                  "textures": { "pixel": "test:item/bare" },
                  "elements": [
                    {
                      "from": [0, 0, 8],
                      "to": [16, 16, 8],
                      "faces": { "south": { "texture": "pixel" } }
                    }
                  ]
                }
                """);
        writePng(pack, "assets/test/textures/item/bare.png", solidImage(1, 1, 0xFFB04020));

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            assertEquals(0xFFB04020, backend.render(ItemRenderRequest.of("test:item/bare", 8)).getRGB(4, 4));
        }
    }

    @Test
    void keepsLocalGeometryWhenMinecraftSubstitutesAMissingParent() throws Exception {
        Path pack = this.temporaryDirectory.resolve("missing-parent-pack");
        writeText(pack, "assets/test/models/item/child.json", """
                {
                  "parent": "test:block/absent",
                  "gui_light": "front",
                  "textures": { "pixel": "test:item/child" },
                  "elements": [
                    {
                      "from": [0, 0, 8],
                      "to": [16, 16, 8],
                      "faces": { "south": { "texture": "#pixel" } }
                    }
                  ]
                }
                """);
        writePng(pack, "assets/test/textures/item/child.png", solidImage(1, 1, 0xFF30A050));

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            assertEquals(0xFF30A050, backend.render(ItemRenderRequest.of("test:item/child", 8)).getRGB(4, 4));
        }
    }

    @Test
    void rendersInheritedBlockElementsWithGuiTransformAndDirectionalShading() throws Exception {
        Path pack = this.temporaryDirectory.resolve("block-pack");
        writeText(pack, "assets/test/models/block/base.json", """
                {
                  "gui_light": "side",
                  "display": {
                    "gui": {
                      "rotation": [30, 225, 0],
                      "scale": [0.625, 0.625, 0.625]
                    }
                  }
                }
                """);
        writeText(pack, "assets/test/models/block/cube.json", """
                {
                  "parent": "test:block/base",
                  "textures": {
                    "all": "test:block/parent",
                    "top": "test:block/top",
                    "bottom": "test:block/bottom"
                  },
                  "elements": [
                    {
                      "from": [0, 0, 0],
                      "to": [16, 16, 16],
                      "faces": {
                        "down":  { "texture": "#bottom" },
                        "up":    { "texture": "#top" },
                        "north": { "texture": "#all" },
                        "south": { "texture": "#all" },
                        "west":  { "texture": "#all" },
                        "east":  { "texture": "#all" }
                      }
                    }
                  ]
                }
                """);
        writeText(pack, "assets/test/models/item/cube.json", """
                {
                  "parent": "test:block/cube",
                  "textures": { "all": "test:block/child" }
                }
                """);
        writePng(pack, "assets/test/textures/block/parent.png", solidImage(4, 4, 0xFFFF0000));
        writePng(pack, "assets/test/textures/block/child.png", solidImage(4, 4, 0xFFFFC060));
        writePng(pack, "assets/test/textures/block/top.png", solidImage(4, 4, 0xFFFFFF20));
        writePng(pack, "assets/test/textures/block/bottom.png", solidImage(4, 4, 0xFF2040FF));

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            BufferedImage rendered = backend.render(ItemRenderRequest.of("test:item/cube", 64));
            int opaquePixels = 0;
            int topPixels = 0;
            int bottomPixels = 0;
            Set<Integer> colors = new HashSet<>();
            for (int y = 0; y < rendered.getHeight(); y++) {
                for (int x = 0; x < rendered.getWidth(); x++) {
                    int color = rendered.getRGB(x, y);
                    if ((color >>> 24) != 0) {
                        opaquePixels++;
                        colors.add(color);
                        int red = color >>> 16 & 0xFF;
                        int green = color >>> 8 & 0xFF;
                        int blue = color & 0xFF;
                        if (red > 150 && green > 150 && blue < 80) {
                            topPixels++;
                        }
                        if (blue > red && blue > green) {
                            bottomPixels++;
                        }
                    }
                }
            }

            assertTrue(opaquePixels > 900, "the transformed cube should occupy a useful portion of the icon");
            assertTrue(opaquePixels < 3000, "the GUI scale should leave transparent padding");
            assertTrue(colors.size() >= 3, "side lighting should produce distinct face shades");
            assertTrue(topPixels > 200, "the GUI transform should expose the up face");
            assertEquals(0, bottomPixels, "the down face must remain behind the opaque cube");
            assertNotEquals(0, rendered.getRGB(32, 32) >>> 24);
        }
    }

    @Test
    void selectsTheFirstFrameFromAnimatedTextureMetadata() throws Exception {
        Path pack = this.temporaryDirectory.resolve("animated-pack");
        writeGeneratedParent(pack);
        writeText(pack, "assets/test/models/item/animated.json", """
                {
                  "parent": "minecraft:item/generated",
                  "textures": { "layer0": "test:item/animated" }
                }
                """);
        BufferedImage strip = new BufferedImage(2, 4, BufferedImage.TYPE_INT_ARGB);
        fill(strip, 0, 0, 2, 2, 0xFFFF3000);
        fill(strip, 0, 2, 2, 2, 0xFF0050FF);
        writePng(pack, "assets/test/textures/item/animated.png", strip);
        writeText(pack, "assets/test/textures/item/animated.png.mcmeta", """
                { "animation": { "frames": [0, 1] } }
                """);

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            BufferedImage rendered = backend.render(ItemRenderRequest.of("test:item/animated", 8));
            assertEquals(0xFFFF3000, rendered.getRGB(4, 4));
        }
    }

    @Test
    void rendersFusionConnectingModelsWithOrdinaryAndPiecedTextures() throws Exception {
        Path pack = this.temporaryDirectory.resolve("fusion-pack");
        writeGeneratedParent(pack);
        writeText(pack, "assets/test/models/item/ordinary.json", """
                {
                  "loader": "fusion:model",
                  "type": "fusion:connecting",
                  "parent": "minecraft:item/generated",
                  "connections": { "default": { "type": "fusion:is_same_block" } },
                  "textures": { "layer0": "test:item/ordinary" }
                }
                """);
        writePng(pack, "assets/test/textures/item/ordinary.png", solidImage(2, 2, 0xFF20C060));

        writeText(pack, "assets/test/models/item/pieced.json", """
                {
                  "loader": "fusion:model",
                  "type": "connecting",
                  "parent": "minecraft:item/generated",
                  "textures": { "layer0": "test:item/pieced" }
                }
                """);
        BufferedImage sheet = new BufferedImage(10, 2, BufferedImage.TYPE_INT_ARGB);
        fill(sheet, 0, 0, 2, 2, 0xFFFF4020);
        fill(sheet, 2, 0, 8, 2, 0xFF2050FF);
        writePng(pack, "assets/test/textures/item/pieced.png", sheet);
        writeText(pack, "assets/test/textures/item/pieced.png.mcmeta", """
                { "fusion": { "type": "connecting", "layout": "pieced" } }
                """);

        writeText(pack, "assets/test/models/item/overlay.json", """
                {
                  "loader": "fusion:model",
                  "type": "fusion:connecting",
                  "parent": "minecraft:item/generated",
                  "textures": { "layer0": "test:item/overlay" }
                }
                """);
        writePng(pack, "assets/test/textures/item/overlay.png", solidImage(6, 3, 0xFFFFFFFF));
        writeText(pack, "assets/test/textures/item/overlay.png.mcmeta", """
                { "fusion": { "type": "connecting", "layout": "overlay" } }
                """);

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            BufferedImage ordinary = backend.render(ItemRenderRequest.of("test:item/ordinary", 8));
            assertEquals(0xFF20C060, ordinary.getRGB(4, 4));

            BufferedImage pieced = backend.render(ItemRenderRequest.of("test:item/pieced", 8));
            assertEquals(0xFFFF4020, pieced.getRGB(4, 4));

            BufferedImage overlay = backend.render(ItemRenderRequest.of("test:item/overlay", 8));
            assertEquals(0, overlay.getRGB(4, 4));
        }
    }

    @Test
    void selectsTheFirstDeclaredFusionLayoutAnimationFrame() throws Exception {
        Path pack = this.temporaryDirectory.resolve("fusion-animation-pack");
        writeGeneratedParent(pack);
        writeText(pack, "assets/test/models/item/animated.json", """
                {
                  "loader": "fusion:model",
                  "type": "fusion:connecting",
                  "parent": "minecraft:item/generated",
                  "textures": { "layer0": "test:item/animated_fusion" }
                }
                """);
        BufferedImage sheet = new BufferedImage(10, 4, BufferedImage.TYPE_INT_ARGB);
        fill(sheet, 0, 0, 2, 2, 0xFFFF3000);
        fill(sheet, 0, 2, 2, 2, 0xFF0050FF);
        writePng(pack, "assets/test/textures/item/animated_fusion.png", sheet);
        writeText(pack, "assets/test/textures/item/animated_fusion.png.mcmeta", """
                {
                  "animation": { "frames": [1, 0] },
                  "fusion": { "type": "fusion:connecting", "layout": "pieced" }
                }
                """);

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            BufferedImage rendered = backend.render(ItemRenderRequest.of("test:item/animated", 8));
            assertEquals(0xFF0050FF, rendered.getRGB(4, 4));
        }
    }

    @Test
    void rejectsFusionFeaturesWithoutAnIsolatedItemRepresentation() throws Exception {
        Path pack = this.temporaryDirectory.resolve("unsupported-fusion-pack");
        writeGeneratedParent(pack);
        writeText(pack, "assets/test/models/item/model_type.json", """
                {
                  "loader": "fusion:model",
                  "type": "fusion:custom",
                  "parent": "minecraft:item/generated",
                  "textures": { "layer0": "test:item/plain" }
                }
                """);
        writePng(pack, "assets/test/textures/item/plain.png", solidImage(1, 1, 0xFFFFFFFF));

        writeText(pack, "assets/test/models/item/layout.json", """
                {
                  "loader": "fusion:model",
                  "type": "fusion:connecting",
                  "parent": "minecraft:item/generated",
                  "textures": { "layer0": "test:item/layout" }
                }
                """);
        writePng(pack, "assets/test/textures/item/layout.png", solidImage(6, 3, 0xFFFFFFFF));
        writeText(pack, "assets/test/textures/item/layout.png.mcmeta", """
                { "fusion": { "type": "connecting", "layout": "future" } }
                """);

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            ItemRenderException modelFailure = assertThrows(
                    ItemRenderException.class,
                    () -> backend.render(ItemRenderRequest.of("test:item/model_type", 8))
            );
            assertEquals(ItemRenderException.Kind.UNSUPPORTED_FEATURE, modelFailure.kind());
            assertEquals("Fusion model type fusion:custom", modelFailure.detail());

            ItemRenderException textureFailure = assertThrows(
                    ItemRenderException.class,
                    () -> backend.render(ItemRenderRequest.of("test:item/layout", 8))
            );
            assertEquals(ItemRenderException.Kind.UNSUPPORTED_FEATURE, textureFailure.kind());
            assertEquals("Fusion connecting texture layout future", textureFailure.detail());
        }
    }

    @Test
    void reportsCustomModelLoaderInsteadOfRenderingAnApproximation() throws Exception {
        Path pack = this.temporaryDirectory.resolve("custom-pack");
        writeText(pack, "assets/test/models/item/custom.json", """
                { "loader": "test:runtime_model" }
                """);

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            UnsupportedItemModelException exception = assertThrows(
                    UnsupportedItemModelException.class,
                    () -> backend.render(ItemRenderRequest.of("test:item/custom", 16))
            );
            assertEquals(
                    "Item model test:item/custom requires unsupported feature: custom model loader test:runtime_model",
                    exception.getMessage()
            );
        }
    }

    @Test
    void extrudesGeneratedModelsWithNonStandardGuiTransforms() throws Exception {
        Path pack = this.temporaryDirectory.resolve("transformed-generated-pack");
        writeGeneratedParent(pack);
        writeText(pack, "assets/test/models/item/transformed.json", """
                {
                  "parent": "minecraft:item/generated",
                  "textures": { "layer0": "test:item/transformed" },
                  "display": { "gui": { "rotation": [0, 45, 0] } }
                }
                """);
        writePng(pack, "assets/test/textures/item/transformed.png", solidImage(1, 1, 0xFFFFFFFF));

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            BufferedImage rendered = backend.render(ItemRenderRequest.of("test:item/transformed", 32));
            assertTrue(opaquePixels(rendered) > 300);
            assertEquals(0, rendered.getRGB(0, 16));
            assertNotEquals(0, rendered.getRGB(16, 16) >>> 24);
        }
    }

    @Test
    void appliesNeoForgeRootTransformsAndCompositeVisibility() throws Exception {
        Path pack = this.temporaryDirectory.resolve("root-transform-pack");
        writeText(pack, "assets/test/models/item/transformed_composite.json", """
                {
                  "loader": "neoforge:composite",
                  "gui_light": "front",
                  "visibility": { "hidden": false },
                  "children": {
                    "hidden": {
                      "textures": { "pixel": "test:item/red" },
                      "elements": [{
                        "from": [0, 0, 8], "to": [16, 16, 8],
                        "faces": { "south": { "texture": "#pixel" } }
                      }]
                    },
                    "moved": {
                      "transform": {
                        "origin": "corner",
                        "translation": [0.5, 0, 0],
                        "rotation": [0, 0, 0, 1]
                      },
                      "textures": { "pixel": "test:item/green" },
                      "elements": [{
                        "from": [0, 0, 8], "to": [8, 16, 8],
                        "faces": { "south": { "texture": "#pixel" } }
                      }]
                    }
                  },
                  "item_render_order": ["hidden", "moved"]
                }
                """);
        writePng(pack, "assets/test/textures/item/red.png", solidImage(1, 1, 0xFFFF2020));
        writePng(pack, "assets/test/textures/item/green.png", solidImage(1, 1, 0xFF20D050));

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            BufferedImage rendered = backend.render(ItemRenderRequest.of("test:item/transformed_composite", 32));
            assertEquals(0, rendered.getRGB(4, 16));
            assertEquals(0xFF20D050, rendered.getRGB(24, 16));
        }
    }

    @Test
    void selectsTheGuiBranchOfNeoForgeSeparateTransforms() throws Exception {
        Path pack = this.temporaryDirectory.resolve("separate-transforms-pack");
        writeText(pack, "assets/test/models/item/separate.json", """
                {
                  "loader": "neoforge:separate_transforms",
                  "base": {
                    "parent": "minecraft:item/generated",
                    "textures": { "layer0": "test:item/red" }
                  },
                  "perspectives": {
                    "gui": {
                      "parent": "minecraft:item/generated",
                      "textures": { "layer0": "test:item/green" }
                    }
                  }
                }
                """);
        writeGeneratedParent(pack);
        writePng(pack, "assets/test/textures/item/red.png", solidImage(1, 1, 0xFFFF2020));
        writePng(pack, "assets/test/textures/item/green.png", solidImage(1, 1, 0xFF20D050));

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            BufferedImage rendered = backend.render(ItemRenderRequest.of("test:item/separate", 16));
            assertEquals(0xFF20D050, rendered.getRGB(8, 8));
        }
    }

    @Test
    void rendersNeoForgeObjModelsFromArchiveResources() throws Exception {
        Path pack = this.temporaryDirectory.resolve("obj-pack");
        writeText(pack, "assets/test/models/item/obj_base.json", """
                {
                  "loader": "neoforge:obj",
                  "gui_light": "front",
                  "model": "test:models/item/plane.obj",
                  "textures": { "surface": "test:item/green" },
                  "visibility": { "visible": false, "hidden": false }
                }
                """);
        writeText(pack, "assets/test/models/item/obj.json", """
                {
                  "parent": "test:item/obj_base",
                  "visibility": { "visible": true }
                }
                """);
        writeText(pack, "assets/test/models/item/plane.obj", """
                mtllib plane.mtl
                v 0 0 0.5
                v 1 0 0.5
                v 1 1 0.5
                v 0 1 0.5
                g visible
                usemtl surface
                f 1/1 2/2 3/3 4/4
                g hidden
                usemtl hidden
                f 1/1 2/2 3/3 4/4
                vt 0 1
                vt 1 1
                vt 1 0
                vt 0 0
                """);
        writeText(pack, "assets/test/models/item/plane.mtl", """
                newmtl surface
                Kd 1 1 1
                map_Kd #surface
                newmtl hidden
                Kd 1 0 0
                map_Kd #surface
                """);
        writePng(pack, "assets/test/textures/item/green.png", solidImage(2, 2, 0xFF20D050));

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            BufferedImage rendered = backend.render(ItemRenderRequest.of("test:item/obj", 32));
            assertEquals(0xFF20D050, rendered.getRGB(16, 16));
            assertTrue(opaquePixels(rendered) > 900);
        }
    }

    @Test
    void appliesInheritedAndPerFaceNeoForgeData() throws Exception {
        Path pack = this.temporaryDirectory.resolve("face-data-pack");
        writeText(pack, "assets/test/models/item/face_data.json", """
                {
                  "gui_light": "side",
                  "textures": { "pixel": "test:item/white" },
                  "elements": [
                    {
                      "from": [0, 0, 8],
                      "to": [8, 16, 8],
                      "neoforge_data": {
                        "color": "FF20C040",
                        "block_light": 15,
                        "ambient_occlusion": false
                      },
                      "faces": {
                        "south": { "texture": "#pixel" }
                      }
                    },
                    {
                      "from": [8, 0, 8],
                      "to": [16, 16, 8],
                      "neoforge_data": { "color": "FF20C040" },
                      "faces": {
                        "south": {
                          "texture": "#pixel",
                          "neoforge_data": {
                            "color": "FFB03020",
                            "sky_light": 15
                          }
                        }
                      }
                    }
                  ]
                }
                """);
        writePng(pack, "assets/test/textures/item/white.png", solidImage(1, 1, 0xFFFFFFFF));

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            BufferedImage rendered = backend.render(ItemRenderRequest.of("test:item/face_data", 64));
            assertEquals(0xFF20C040, rendered.getRGB(16, 32));
            assertEquals(0xFFB03020, rendered.getRGB(48, 32));
        }
    }

    @Test
    void rendersCompositeChildrenInDeclaredItemOrder() throws Exception {
        Path pack = this.temporaryDirectory.resolve("composite-pack");
        writeText(pack, "assets/test/models/item/composite.json", """
                {
                  "loader": "neoforge:composite",
                  "gui_light": "front",
                  "children": {
                    "overlay": {
                      "textures": { "layer": "test:item/overlay" },
                      "elements": [
                        {
                          "from": [0, 0, 8],
                          "to": [16, 16, 8],
                          "faces": { "south": { "texture": "#layer" } }
                        }
                      ]
                    },
                    "base": {
                      "textures": { "layer": "test:item/base" },
                      "elements": [
                        {
                          "from": [0, 0, 8],
                          "to": [16, 16, 8],
                          "faces": { "south": { "texture": "#layer" } }
                        }
                      ]
                    }
                  },
                  "item_render_order": ["base", "overlay"]
                }
                """);
        writePng(pack, "assets/test/textures/item/base.png", solidImage(2, 2, 0xFF2050D0));
        BufferedImage overlay = solidImage(2, 2, 0xFFD03020);
        overlay.setRGB(0, 0, 0);
        writePng(pack, "assets/test/textures/item/overlay.png", overlay);

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            BufferedImage rendered = backend.render(ItemRenderRequest.of("test:item/composite", 32));
            assertEquals(0xFF2050D0, rendered.getRGB(4, 4));
            assertEquals(0xFFD03020, rendered.getRGB(24, 24));
        }
    }

    @Test
    void appliesItemLayerNeoForgeDataWithoutFlatteningLayerOrder() throws Exception {
        Path pack = this.temporaryDirectory.resolve("item-layers-pack");
        writeGeneratedParent(pack);
        writeText(pack, "assets/test/models/item/layered.json", """
                {
                  "parent": "minecraft:item/generated",
                  "loader": "neoforge:item_layers",
                  "textures": {
                    "layer0": "test:item/layer_base",
                    "layer1": "test:item/layer_overlay"
                  },
                  "render_types": {
                    "minecraft:cutout": [0],
                    "minecraft:translucent": [1]
                  },
                  "neoforge_data": {
                    "layers": {
                      "1": {
                        "color": "FFFF3020",
                        "block_light": 15,
                        "sky_light": 15
                      }
                    }
                  }
                }
                """);
        writePng(pack, "assets/test/textures/item/layer_base.png", solidImage(2, 2, 0xFF2040C0));
        BufferedImage overlay = solidImage(2, 2, 0xFFFFFFFF);
        overlay.setRGB(0, 0, 0);
        writePng(pack, "assets/test/textures/item/layer_overlay.png", overlay);

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack)) {
            BufferedImage rendered = backend.render(ItemRenderRequest.of("test:item/layered", 16));
            assertEquals(0xFF2040C0, rendered.getRGB(2, 2));
            assertEquals(0xFFFF3020, rendered.getRGB(12, 12));
        }
    }

    @Test
    void discoversItemModelsAndCompletesBatchAfterUnsupportedModel() throws Exception {
        Path pack = this.temporaryDirectory.resolve("batch-pack");
        writeGeneratedParent(pack);
        writeText(pack, "assets/test/models/item/good.json", """
                {
                  "parent": "minecraft:item/generated",
                  "textures": { "layer0": "test:item/good" }
                }
                """);
        writeText(pack, "assets/test/models/item/unsupported.json", """
                { "loader": "test:runtime_model" }
                """);
        writeText(pack, "assets/test/models/block/not_an_item.json", "{}\n");
        writePng(pack, "assets/test/textures/item/good.png", solidImage(1, 1, 0xFF40A060));

        Path duplicate = this.temporaryDirectory.resolve("duplicate.zip");
        writeArchive(duplicate, Map.of(
                "assets/test/models/item/good.json",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"test:item/good\"}}"
                        .getBytes(StandardCharsets.UTF_8)
        ));

        try (ItemRenderBackend backend = ItemRenderBackend.open(pack, duplicate)) {
            List<ItemModelId> models = backend.discoverItemModels();
            assertEquals(List.of(
                    ItemModelId.parse("minecraft:item/generated"),
                    ItemModelId.parse("test:item/good"),
                    ItemModelId.parse("test:item/unsupported")
            ), models);

            int[] callbacks = {0};
            ItemRenderBatchResult diagnostic = backend.renderBatch(
                    models.stream()
                            .filter(model -> model.namespace().equals("test"))
                            .map(model -> new ItemRenderRequest(model, 16))
                            .toList(),
                    ItemRenderBatchOptions.DIAGNOSTICS,
                    ignored -> callbacks[0]++
            );
            assertEquals(2, callbacks[0]);
            assertEquals(1, diagnostic.succeededCount());
            assertEquals(1, diagnostic.failedCount());
            assertTrue(diagnostic.entries().get(0).succeeded());
            assertNull(diagnostic.entries().get(0).image());
            ItemRenderException failure = (ItemRenderException) diagnostic.entries().get(1).failure();
            assertEquals(ItemRenderException.Kind.UNSUPPORTED_FEATURE, failure.kind());
            assertEquals("custom model loader test:runtime_model", failure.detail());
            assertTrue(diagnostic.elapsedNanos() >= diagnostic.entries().get(0).elapsedNanos());

            ItemRenderBatchResult retained = backend.renderBatch(List.of(
                    ItemRenderRequest.of("test:item/good", 16)
            ));
            assertTrue(retained.entries().getFirst().succeeded());
            assertTrue(retained.entries().getFirst().image() != null);
        }
    }

    private static void writeGeneratedParent(Path pack) throws IOException {
        writeText(pack, "assets/minecraft/models/item/generated.json", """
                { "parent": "builtin/generated", "gui_light": "front" }
                """);
    }

    private static void writeText(Path pack, String resourcePath, String value) throws IOException {
        Path target = pack.resolve(resourcePath.replace('/', File.separatorChar));
        Files.createDirectories(target.getParent());
        Files.writeString(target, value, StandardCharsets.UTF_8);
    }

    private static void writePng(Path pack, String resourcePath, BufferedImage image) throws IOException {
        Path target = pack.resolve(resourcePath.replace('/', File.separatorChar));
        Files.createDirectories(target.getParent());
        assertTrue(ImageIO.write(image, "png", target.toFile()));
    }

    private static byte[] pngBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static void writeArchive(Path archive, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private static BufferedImage solidImage(int width, int height, int color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        fill(image, 0, 0, width, height, color);
        return image;
    }

    private static void fill(BufferedImage image, int startX, int startY, int width, int height, int color) {
        for (int y = startY; y < startY + height; y++) {
            for (int x = startX; x < startX + width; x++) {
                image.setRGB(x, y, color);
            }
        }
    }

    private static int opaquePixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }
}
