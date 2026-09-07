# Offline item rendering

`ItemRenderBackend` reads captured resource directories, JARs and ZIP files and produces ARGB previews with a software renderer. It needs no Minecraft process, GPU context or runtime-rendered fallback. Callers supply resolved model IDs, output size and tint colors through `ItemRenderRequest`. The backend does not discover registered items, evaluate model predicates or execute mod code.

## Run diagnostics

From the repository root, use the checked-in wrapper with JDK 21:

```powershell
.\gradlew.bat :companion:test --tests '*itemrender.*'
.\gradlew.bat :companion:itemRenderHarness '--args=--root=<minecraft-resources.jar> --root=<neoforge-client.jar> --mods=<mods-directory> --output=build/reports/item-render'
```

Relative paths resolve from `companion/`. Use `--help` to list filtering and image output options. The runner writes `summary.txt`, `summary.json` and `models.csv`. Add `--write-images` to retain previews.

For large captured packs, save the ordered root paths as a JSON array and pass `--roots-file=<file.json>`. Those roots are inserted at that argument's position, so later `--root` arguments can override them. This avoids command-line length limits. Root paths inside the file follow the same working-directory rules as `--root`.

Use repeated `--model=<namespace:path>` arguments to render a selected set instead of discovering every model file. `--namespace` can further filter either set. An absent selected model appears as a failed request in the report.

Use `--root=<archive>!/path/to/pack` for an enabled resource pack stored below an archive directory. Roots apply in argument order, with later roots taking priority. Include the loader's client resources and every enabled built-in pack needed by the captured instance. Missing roots can make supported models fail to resolve parents or textures.

## Supported content and limits

The renderer supports vanilla generated and element models, display transforms, the first listed animation frame, supplied tint colors, NeoForge item layers and composites, root transforms, visibility, separate GUI transforms and OBJ/MTL models. Isolated Fusion support resolves model parents and connecting texture tiles without running the mod.

The vanilla `blocks` atlas supports `single`, `directory`, `filter` and `paletted_permutations` sources, including armor trim textures generated from palette PNGs. Atlas source lists combine in resource-pack order; later definitions override earlier ones, and filters remove earlier sprites. Custom atlas sources, including XyCraft's procedural cloud textures, remain unevaluated. Their presence does not prevent resolving ordinary or known generated sprites.

Loader-specific models backed by executable mod code remain explicit unsupported cases until they receive an isolated integration. Runtime-rendered fallback is deferred. Default model requests cannot reproduce arbitrary ItemStack components, dynamic item colors, world-dependent model decisions or custom item renderers. A successful render does not establish pixel parity with Minecraft.

## Fluid containers

`neoforge:fluid_container` composes the base, a fluid texture clipped to the fluid mask, and an optional cover. It supports inherited container models, `flip_gas`, `cover_is_mask` and `apply_fluid_luminosity`. Mask alpha determines geometry, so a nonzero mask pixel does not reduce the fluid's opacity. Fluid and cover layers have NeoForge's slight depth offsets and use front GUI lighting.

Model JSON supplies a fluid ID, but mod code supplies that fluid's appearance. Add a resource root containing `totaldebug/fluid-appearances.json`:

```json
{
  "schemaVersion": 1,
  "fluids": {
    "example:fluid": {
      "stillTexture": "example:block/fluid_still",
      "tint": "FFFFFFFF",
      "lightLevel": 0,
      "lighterThanAir": false
    }
  }
}
```

The example values illustrate the format. Capture actual values from the matching pack with the [Code-mode capture body](examples/capture-fluid-appearances.java), after client initialization. Save the returned JSON string's contents at that resource path and review its `errors` map. The script reads the fluid registry and returns metadata without writing files or rendering anything. Its APIs were checked against the cached NeoForge 1.21.1 sources; execution against ATM10 is still pending. Resource packs continue to supply the texture pixels. Later roots override earlier entries by fluid ID, and rendering works with Minecraft closed after capture.

`request.withFluid(ItemModelId.parse("example:fluid"))` selects a container's current fluid. A null selection uses the model's default; `minecraft:empty` draws the container without a fluid layer and needs no captured appearance. An explicit tint at index 1 overrides the captured default. Callers must resolve stack-specific fluid components and fullness model predicates themselves. The capture uses a fresh one-bucket `FluidStack`, so it cannot represent every component-dependent tint.

Missing fluid metadata produces `missing captured fluid appearance` with the required fluid ID. No texture path or color is inferred from a name. Animated fluid textures use the existing first-frame support. Animated mask geometry is explicitly unsupported because NeoForge unions opacity across animation frames. The software renderer uses its existing generated-edge extrusion; transformed edge pixels have not been compared with Minecraft.

The captured ATM10 archive set contains 214 models using this loader: 205 modded bucket models and nine JustDireThings canister models. These remain outside the measured success count until a matching fluid appearance capture is available and the scan is rerun. Unit fixtures establish compositor behavior, not pack coverage or Minecraft pixel parity.

## What archive counts mean

The scan visits every `assets/<namespace>/models/item/*.json` file, including nested paths. These include parent templates, predicate targets, trim variants, JEI-only models and compatibility models for absent mods. A trim variant is meaningful only when selected for a matching armor stack. It is not a standalone registered item.

Reports count archive model files processed without failure. They separately count successful images with visible pixels and completely transparent images, including when image retention is disabled. `models.csv` records each image's visible pixel count. These measurements do not establish real ItemStack coverage or correct pixels. An item-coverage report needs a manifest of registered item IDs and representative stack state captured from the matching instance. Fresh rendering can remain offline after capture.

The historical ATM10 Sky scan at renderer revision `2e4fde1` processed 40,674 of 43,913 archive model files without failure, or 92.62 percent. Comparisons must preserve the ordered resource roots and model denominator, and separately inspect representative images and failure categories.

The [September 2026 validation](ITEM_RENDER_VALIDATION.md) records the reconstructed baseline, per-category improvements, pixel comparisons and remaining unsupported cases.

## Fractional Fusion texture regions

Fusion 1.2.12 gives an item quad an isolated tile from a connecting texture. `TextureRegion` keeps the source image and exact pixel bounds so the renderer can sample tiles whose dimensions are not whole pixels.

`rechiseled:block/coal_block_compacted` is an 80 by 16 connecting texture with no declared layout. Fusion uses its default 8 by 6 full layout, giving a tile of 10 by 2.67 pixels. The renderer samples that region directly for element faces and generated layers. Generated edges clip their geometry to partial boundary texels.

Regression fixtures compare a fractional region against an independently constructed texture with the same color proportions. They cover flat and transformed generated models, element faces, animation-frame offsets and integer tile preservation. The renderer does not round the tile or infer a different layout.
