# Offline item rendering

`ItemRenderBackend` reads captured resource directories, JARs and ZIP files and produces ARGB previews with a software renderer. It needs no Minecraft process, GPU context or runtime-rendered fallback. Callers supply resolved model IDs, output size and tint colors through `ItemRenderRequest`. The backend does not discover registered items, evaluate model predicates or execute mod code.

## Run diagnostics

From the repository root, use the checked-in wrapper with JDK 21:

```powershell
.\gradlew.bat :companion:test --tests '*itemrender.*'
.\gradlew.bat :companion:itemRenderHarness '--args=--root=<minecraft-resources.jar> --root=<neoforge-client.jar> --mods=<mods-directory> --output=build/reports/item-render'
```

Relative paths resolve from `companion/`. Use `--help` to list filtering and image output options. The runner writes `summary.txt`, `summary.json` and `models.csv`. Add `--write-images` to retain previews.

Use `--root=<archive>!/path/to/pack` for an enabled resource pack stored below an archive directory. Roots apply in argument order, with later roots taking priority. Include the loader's client resources and every enabled built-in pack needed by the captured instance. Missing roots can make supported models fail to resolve parents or textures.

## Supported content and limits

The renderer supports vanilla generated and element models, display transforms, the first listed animation frame, supplied tint colors, NeoForge item layers and composites, root transforms, visibility, separate GUI transforms and OBJ/MTL models. Isolated Fusion support resolves model parents and connecting texture tiles without running the mod.

Loader-specific models backed by executable mod code remain explicit unsupported cases until they receive an isolated integration. Runtime-rendered fallback is deferred. Default model requests cannot reproduce arbitrary ItemStack components, dynamic item colors, world-dependent model decisions or custom item renderers. A successful render does not establish pixel parity with Minecraft.

## What archive counts mean

The scan visits every `assets/<namespace>/models/item/*.json` file, including nested paths. These include parent templates, predicate targets, trim variants, JEI-only models and compatibility models for absent mods. A trim variant is meaningful only when selected for a matching armor stack. It is not a standalone registered item.

Reports count archive model files processed without failure. They do not measure real ItemStack coverage, nonempty previews or correct pixels. An item-coverage report needs a manifest of registered item IDs and representative stack state captured from the matching instance. Fresh rendering can remain offline after capture.

The historical ATM10 Sky scan at renderer revision `2e4fde1` processed 40,674 of 43,913 archive model files without failure, or 92.62 percent. Comparisons must preserve the ordered resource roots and model denominator, and separately inspect representative images and failure categories.

## Deferred fractional Fusion texture regions

Fusion 1.2.12 normally gives an item quad the isolated tile from a connecting texture. Whole-pixel tiles can be cropped into a `BufferedImage`.

`rechiseled:block/coal_block_compacted` is an 80 by 16 connecting texture with no declared layout. Fusion uses its default 8 by 6 full layout, giving a tile of 10 by 2.67 pixels. Fusion keeps the original image and represents the tile with fractional UV coordinates. The renderer currently reports `fractional Fusion full texture tile` for the six affected block, slab and stair item models.

Supporting this requires preserving the image and UV bounds when resolving textures. Rounding the crop or inferring another layout would change the sampled pixels. A regression fixture must cover a non-integral region and check that the other Fusion previews remain unchanged.
