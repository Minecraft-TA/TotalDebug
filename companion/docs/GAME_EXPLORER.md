# Items and blocks

Open **Browse > Items and blocks** for a separate window with per-mod browsing. Search Everywhere also includes **Items** and **Blocks**, and finds both in **All** by display name or registry ID. Localized names work independently of the class index.

Selecting an entry shows its registry ID, namespace/mod name and version, implementation class, basic default-stack or default-block-state properties, and its item/block counterpart where one exists. Source, class usages and exact registry-ID string usages open in the main editor while the explorer stays open. Class usages describe references to the implementation class; they do not claim to enumerate every gameplay use of an item.

The **Textures and models** page lists JSON references and files actually read while rendering the default inventory model. This includes inherited models, texture files, atlas definitions and palette inputs, and OBJ materials used by supported loaders. Select a resource to see its effective provider, captured resource layers, PNG dimensions/alpha information or text contents. Copy its resource-pack path, open it in the existing resource editor, or export its exact captured bytes. PNG animation metadata is a separate resource and can be exported alongside the image.

## Capture lifecycle

The updated client mod captures game data in the background after publishing its runtime inventory to Companion. Registry/default-stack reads run on the Minecraft client thread; resource copying and compression run on the existing inventory worker. Companion's class index and startup readiness do not wait for resource copying.

The snapshot lives at `total-debug/cache/runtime/game-catalog.zip` inside the selected game instance. It contains:

- `catalog.json`: format 1, matching runtime inventory ID, capture time/language, registry entries, resource-provider lists and per-entry capture warnings.
- `layers/<n>/assets/...`: visible resource versions ordered from lowest to highest priority for each resource. This retains additive atlas definitions as well as texture/model overrides. Layer numbers are per-resource precedence positions, not global pack identifiers.

Minecraft's resource manager determines visible layers and filtering. Effective `.mcmeta` is captured separately because Minecraft excludes metadata files from resource listings. Metadata from below an overridden texture is discarded; a metadata-only pack above that texture is retained. Publication replaces the ZIP atomically, so a failed capture leaves the previous complete snapshot available. Captures have a 64 MiB per-resource and 2 GiB total uncompressed limit; metadata is limited to 4 MiB per file.

The reader checks runtime identity and archive changes before using a snapshot. An older mod with no catalog produces an explicit capture-unavailable message rather than inferring registered items from filenames. Captured files remain usable after Minecraft closes and after the original resource pack is removed.

Use **Browse > Refresh game data** while connected to republish the runtime inventory and capture changed resources. This uses the existing inventory refresh request and does not restart Minecraft. After the game log reports that the catalog was published, use **Reload saved capture** or reopen the explorer. Each open window keeps its capture time visible. Resource reads reject a replaced archive until the view is reloaded.

## First-slice boundaries

- Previews use the captured base inventory model binding. Runtime tint providers, stack-dependent predicates, custom renderers and live world state are not reproduced. Unsupported rendering is reported explicitly. Blocks without an item form still expose their blockstate/model references but have no inventory preview.
- Properties describe the default stack/state at capture time. World-dependent mining behavior, placed block entities, tooltip callbacks, recipes, drops and gameplay relationships are outside this slice.
- Resource references may include alternate blockstate models and overridden declarations. The list is useful for finding source assets, not a claim that every listed texture contributes pixels to the selected preview. Dependencies of an unsupported custom loader can remain incomplete.
- Captured resources identify their game pack provider and can be opened from the capture ZIP. The explorer does not infer an original filesystem archive path from a pack's display ID.
- The capture includes model, blockstate, texture and atlas resources. The optional fluid-appearance data used by the renderer's standalone diagnostic workflow is not yet captured by this integration.

## Verification

Run from the repository root with JDK 21:

```powershell
.\gradlew.bat :companion:test :mod:test :storage:test -PtotaldebugUseMavenLocal=true
```

`GameCatalogPublisherTest` uses Minecraft's actual resource manager and directory packs to verify precedence, additive atlas layers and metadata-only overrides. `GameCatalogTest` checks offline rendering, inherited models, atlas aliases, exact export bytes, localized/scoped search and stale-capture rejection. `GameExplorerWindowTest` checks source navigation without closing the overview and writes an isolated fixture screenshot to `companion/build/ui-screenshots/game-explorer.png`.

The automated fixture checks do not replace a fresh ATM10 capture and a visual check of representative real items before deployment.
