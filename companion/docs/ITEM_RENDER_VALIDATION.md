# Offline renderer validation, September 7, 2026

The renderer was ported from standalone Companion revision `2e4fde1` into the TotalDebug 1.21.1 monorepo at `b29fc47`. The port contains 14 production Java files and three test/diagnostic files. It reuses the current Gson dependency and adds no external libraries or application/protocol wiring. See [offline item rendering](ITEM_RENDERING.md) for the API and runner.

## Baseline and resource inputs

The historical ATM10 Sky report used 323 ordered resource roots. Eight original paths no longer existed. The reconstruction substituted the cached Minecraft 1.21.1 client and NeoForge 21.1.201 universal archives, same-named backups for EuphoriaPatcher, Generator Galore, JEI and WATUT, the disabled WorldEdit archive, and a saved TotalDebug snapshot. The snapshot contains no item models. Original bytes were unavailable, so equivalence of substituted archives is not claimed.

The reconstructed scan has one additional model, `framedblocks:item/framed_axe`. The current FramedBlocks archive has a later modification date than the historical report. All 43,913 shared model IDs retain exactly the historical status, failure kind and failure detail with the unmodified port. No historical model disappeared.

Code comparisons use the same reconstructed 323-root list for both baseline and final scans. Historical differences are kept separate. Local evidence under the worktree's `build/archive-baseline/` includes `roots.json`, `substitutions.json`, `final-root-hashes.json`, `historical-comparison.json` and `final-comparison.json`. These generated artifacts and pack archives are not checked in.

## Archive model results

All scans below render at 32 pixels. Counts describe model-file requests, including templates and variants. They do not measure registered items, representative ItemStacks or pixel parity with Minecraft.

| Renderer and inputs | Model files | Without failure | With visible pixels | Empty successful images | Failed |
| --- | ---: | ---: | ---: | ---: | ---: |
| Historical standalone report | 43,913 | 40,674 | Not measured | Not measured | 3,239 |
| Unmodified port, reconstructed inputs | 43,914 | 40,675 | 40,669 | 6 | 3,239 |
| Final renderer, same reconstructed inputs | 43,914 | 41,451 | 41,445 | 6 | 2,463 |

The final completion rate is 94.39 percent, compared with 92.62 percent for the reconstructed baseline. There are 776 recovered models, no new failures, no model-set changes and no changes to the remaining failure kinds or details.

Palette-generated trim textures account for 770 recoveries: Minecraft 250, Mekanism Tools 240, Just Dire Things 160, Create 40, Immersive Engineering 40 and Useful Slime 40. Exact fractional Fusion regions recover the six Rechiseled coal block, slab and stair variants.

The six successful transparent images are `ars_nouveau:item/light_block`, `ars_nouveau:item/portal`, `integrateddynamics:item/invisible_light`, `mekanism:item/meka_tool_left`, `notenoughwands:item/light` and `supplementaries:item/altimeter_overlay`. Reports retain these separately from visible previews.

The baseline visibility pass reused the original renderer/model classes with only the new batch pixel-counting/report code. It reproduced all original baseline outcomes. Its purpose was to measure empty output without incorporating rendering fixes.

## Pixel checks and tests

All 536 Companion tests pass under JDK 21. Regression fixtures demonstrated the original failures before verifying the fixes:

- Half-alpha quads now blend each shared triangle-edge sample once, including mirrored winding.
- Generated edges sample the opaque boundary texel instead of its transparent neighbor.
- Composite children share depth sorting, so a farther child cannot overwrite a translucent foreground because of declaration order.
- Generated front/back faces no longer both contribute alpha. Fixtures cover rotated views, a reversed view and reflected GUI/root transforms.
- Palette tests cover RGB/alpha mapping, additive resource ordering, aliases, filters, missing later sources, cache clearing and colliding generated sprite names.
- Fractional Fusion tests compare exact color-band proportions against an independently constructed texture for flat generated layers, transformed extrusion and element faces. They also check integer tiles and animation-frame offsets.

Five representative previews exactly match the stored historical PNGs at their original sizes: Create adjustable chain gearshift, Create andesite encased cogwheel, GeOre allthemodium GUI spyglass, ConnectedGlass tinted borderless glass and Mekanism uranium ore. All seven renderable samples from the reconstructed baseline, including a diamond and blue stained glass, remain identical at every ARGB pixel after the fixes.

The final nine-model sample also includes newly rendered trimmed diamond boots and a Rechiseled coal block. All six formerly unsupported coal variants render. Contact sheets were inspected for intact silhouettes, visible texture bands, transparency and missing-texture artifacts. Coal previews are dark. No fresh game reference was captured, so these checks establish consistency and targeted pixel correctness rather than general game parity.

## Performance check

The real pack's blocks atlas contains 1,304 sources, including 1,272 single-texture declarations. Parsing them for every uncached texture caused avoidable overhead. The final resolver parses immutable source definitions once and stores palette textures and permutations separately, preserving resource order without allocating their Cartesian product.

For the same 1,433 sampled existing texture IDs across 323 roots, the warm median resolver-only time fell from 3,748 ms to 109 ms with zero lookup failures. This is a lookup benchmark, not a whole-renderer speed ratio. The final full scan took 93.315 seconds; shared host load varied between runs, so scan timings are not treated as a controlled performance comparison.

## Remaining failures

| Failure kind | Models | Examples and boundary |
| --- | ---: | --- |
| Unsupported feature | 1,294 | 466 builtin/entity models, 214 NeoForge fluid containers, 171 XyCraft connected-texture models, 148 Modern Industrialization machines and other custom loaders |
| Missing resource | 761 | Includes 319 XyCraft procedural cloud textures; custom atlas code remains unevaluated |
| No geometry | 275 | Empty/template models, generated models without layer0 and an OBJ with no visible faces |
| Render error | 77 | Unresolved texture variables |
| Invalid model | 55 | 46 unsupported element rotations, eight malformed JSON files and one out-of-range element |
| Resource error | 1 | Invalid first animation-frame index on `allthetweaks:item/improbable_probability_device` |

The backend does not execute item predicates, arbitrary mod loaders or custom renderers. A manifest of registered item IDs and representative stack state is still needed to measure real ItemStack coverage. Runtime-rendered fallback remains deferred. These limits do not prevent generating new previews from supported captured resources while Minecraft is closed.

## Reproduce locally

Use a full JDK 21 and the root wrapper. The local validation used the already available SCNet/JIndex Maven Local artifacts as described in the [build guide](../../docs/BUILD_RELEASE.md).

```powershell
.\gradlew.bat :companion:test -PtotaldebugUseMavenLocal=true
.\gradlew.bat :companion:itemRenderHarness -PtotaldebugUseMavenLocal=true '--args=--roots-file=<absolute-path-to-roots.json> --output=<absolute-report-directory>'
```

Per-model CSVs and summaries are in the worktree's `build/reports/item-render/atm10sky-port-baseline`, `atm10sky-baseline-metrics`, `atm10sky-atlas` and `atm10sky-final`. Representative PNGs, exact comparisons and six-coal before/after images are under `build/archive-baseline/`.
