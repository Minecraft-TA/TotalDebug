# Storage and caches

TotalDebug keeps instance-authored files with the Minecraft workspace and application settings in Companion's global home. Generated caches are replaceable; scripts and debugger state are user data.

## Instance files

The instance home is `{game-directory}/total-debug`. Development client and server runs use `run/total-debug` inside the repository, alongside the rest of the development game files.

```text
total-debug/
  scripts/
    Example.tdscript
  state.json
  changes.json
  cache/
    runtime/
      .lock
      inventory.json
      catalog.json
      sources/
        manifest.json
        fabric-renderer-api-v1.jar
        net.neoforged.neoforge.jar
      index.jindex
    decompiled/
      .lock
      manifest.json
      net.minecraft.world.level.block.Blocks.java
      net.minecraft.world.level.block.Blocks.debug
    inspection-previews/
      <uuid>.zip
```

Files are created when needed; an empty instance does not need every directory.

- `scripts` contains authored methodless Java scripts. It is created when the first script is saved, not when a project is opened. Opening and closing with default state does not create `state.json`.
- `state.json` holds watches, breakpoint definitions, mute/exception choices and the last 50 distinct evaluator inputs with imports and execution side. The [project scope](../companion/README.md#ownership) owns instance state and flushes it before retiring the project. One instance-state owner writes the whole file. Breakpoint resolution is partitioned by runtime signature.
- `changes.json` records what Companion changed in the pack and is still in effect: for each configuration setting the mod, file and setting, for each key binding its name, and for both the value before the first change, the value written last and when. It is user data, written by one owner like `state.json`, and only once something changed. See [the Modpack tree](MODPACK.md#the-change-record).
- The one replaceable inventory describes the Java runtime, production mode, ordered physical class sources, logical origins and module ownership. Minecraft owns this file; Companion never writes a local scan into it. Game and Companion use the same Java record, JSON format and validator.
- `catalog.json` is the pack catalog: installed mods with their versions, dependencies, configuration files and original mod files, plus the entries of the registries it lists (blocks, items, entity types, fluids and sound events) in one shape per registry, how items are drawn where that is not their conventional model, every key binding with its default key, context and registering mod, the conflicts between key contexts, and the names the keyboard layout gives keys. Minecraft owns it like the inventory: it captures the catalog on the client thread after resources load, writes it once per inventory id and language, and announces it to Companion. Companion reads the saved file only when its inventory id matches `inventory.json`.
- `inspection-previews` holds immutable archives of the winning models, textures and atlases that Companion uses to draw item icons. The game keeps the newest archive; Companion restores it when a project opens, so icons remain available offline.
- The format-2 source manifest lists generated JAR names, effective-content fingerprints, sizes and output SHA-256 hashes. Physical directories and JARs are referenced in place. A virtual root reuses its original archive only after its class entries and manifest match the effective loader view. Nested archives are copied as bytes; filtered or merged views are packed with buffered, compressed ZIP output. Filenames use the artifact or Java module name; collisions receive a numeric suffix. Only changed or damaged files are regenerated, and obsolete generated JARs are removed.
- `index.jindex` is the only index file. Its ZIP contains the native Zstd `index` entry first, followed by a format-2 `manifest.json` with source kind, identity and source-id mappings. Local manifests also store archive fingerprints and incomplete-input diagnostics. Local identity includes the Java runtime and ordered archive content hashes; runtime identity comes from the published inventory. There is no index directory, generation selector or `current.json`.
- Decompiled filenames normally use the full binary class name. The manifest records runtime/decompiler identity and any shortened or disambiguated filenames. Reserved names are escaped, case-insensitive collisions receive a numeric suffix, and long names retain their beginning and end.
- Each `.debug` file contains the binary name, source checksum, line mappings and variable names. The manifest exposes a pair only after both files are written. Readers load and verify both files under the cache lock. Unlisted files from interrupted writes are reclaimed when the store opens.
- There is one current runtime, not retained generations. Source replacement, compilation and index/bytecode reads share the runtime lock. Readers validate their expected identity and fail if the runtime changed. Replacing the runtime or decompiler clears its generated source pairs; closed services cannot publish late work, and old runtime editor/usage tabs are retired.

Source identities hash effective class contents and manifest bytes for directories, and archive bytes for direct archive inputs. The aggregate includes ordered physical representations and module names, without loader filesystem creation counters. Proven duplicate archives retain the first source's ownership and precedence. Offline indexes still depend on referenced JARs, directories and the Java installation being present. This layout does not make an offline capture portable.

Cache reuse is optional. Startup and live inventory handling validate the current inventory first, then reuse a matching readable index or rebuild it once in place. Missing, invalid or unsupported generated source manifests and indexes are recreated, not migrated. Minecraft retains the discovered original source paths, but revalidates their generated copies when requested. Missing original runtime sources and failed rebuilds remain errors; authored files are never part of cache recovery.

A new game connection suspends compilation and retires any unconfirmed offline restore. Cached browsing can retain its index, but compilation resumes only after the live inventory is validated and installed. Matching live inventory announcements join pending work for that inventory and supersede pending local work. Reopening a project, retrying indexing, or rescanning local sources cannot schedule an offline restore during a live handshake. A cold build retains the native index loaded during staged-file validation and installs it after atomic publication. Closing or superseding a request cancels later phases and discards late results. Native operations keep ownership until they return; installed-index disposal still waits for active queries.

Without a usable saved runtime, Companion browses original top-level mod JARs and builds a local index in the same file. It does not copy them into Minecraft's prepared-source directory. Local sources are hashed during preparation and verified before publication. Source reads check file metadata; a detected content change invalidates that binding and schedules a rescan. Cached decompilation validation runs off the EDT and does not wait behind a cold decompilation. A damaged saved runtime is preserved while local fallback reports its failure. Read-only instances remain browsable when index persistence fails.

## Global application files

Default Windows home: `%LOCALAPPDATA%/TotalDebugCompanion`. Override it with `--app-home` or `totaldebug.companionAppHome`.

```text
TotalDebugCompanion/
  settings.json
  projects.json
  run/
    companion/
      instance.lock
      instance.properties
      instance.key
      mcp-endpoint.json
  cache/
    apps/
      .lock
      <jar-content-hash>/
        TotalDebugCompanion.jar
    jdt/
      .plugins/dummyBundle/
    mcp/
      work/Tomcat/127.0.0.1/ROOT/
  logs/
    .lock
    <UTC-time>-<UUID>/
      .lock
      companion.log
      previous.log
```

- `settings.json` contains appearance, fonts, debugger window geometry and presentation preferences. It contains no watches, breakpoints or expression history.
- `projects.json` remembers known projects by instance identity, TotalDebug data directory and game directory, plus the selected project and an optional `nameOverride`. Automatic names are derived from directories. Entries retain selection order for the recent-project menu. Companion restores its cached data on standalone startup. The previously remembered `profile.json` is imported once and removed after the registry is saved; instance files stay in place.
- Instance identity is derived from the normalized game-directory path. Moving a directory requires reopening that location and may require rebuilding its cache; this registry does not relocate installations.
- The `run/companion` files coordinate the existing single Companion process. Credentials are published with user-only POSIX permissions or Windows ACLs. Lock ownership, not the existence of a lock file, determines liveness.
- Immutable launch copies retain the three most recently used builds, plus any older build still pinned by a launching or running process. Publishers and pruning share a cache lock; the launcher pins the JAR through process exit, and Companion also pins its running copy. Authored scripts and installed executables are outside this cleanup scope.
- The JDT directory holds embedded Eclipse plugin metadata. The dummy bundle is part of the JDT adapter, not a Minecraft plugin.
- The MCP directory is Tomcat's reconstructible work area, not an execution store.
- Each launch has a unique log directory. Java output rotates between two files, each at most 4 MiB once Companion's logger starts. Up to ten recent log directories are retained, plus any older active launch. The launcher captures JVM/bootstrap failures in the same `companion.log` before application logging starts. Those pre-application bytes are not subject to the Java logger's size limit. Per-directory leases protect active logs; root locks serialize creation and pruning.

## Installed executable and loader-owned files

The installed executable stays at:

```text
{actual-game-directory}/total-debug/companion-app/TotalDebugCompanion.jar
```

TotalDebug downloads and verifies its paired Companion release only when this JAR is missing. Existing files are preserved, including manual development replacements. Updating to a new application pair requires replacing or removing the installed JAR explicitly.

For a dev run this is beneath `run/total-debug`, alongside instance-authored files. A configured development JAR bypasses this installed payload. `localBundle` produces a flat pair of JARs under `build/local-bundle`. `deployLocal` installs them into the explicitly configured Minecraft directory and points its client configuration at the mutable Companion build. See the README deployment instructions.

NeoForge configuration, Minecraft logs/options, launcher files and other mods' files remain in their original owners' directories.

## Execution lifetime

MCP jobs keep generated source, status, logs, structured values and runtime context in memory. `job_source` reads the same retained record. Completed jobs are evicted oldest-first on submission when the 256-record retention target is exceeded; active jobs are never evicted. Companion exit discards all records.

The evaluator's input-recall list belongs in `state.json`. Save useful code explicitly as a script.

## Ownership and publication

The internal `storage` Gradle module in TotalDebug owns paths, atomic operations, file leases, launch/log retention and shared inventory/launch contracts. The mod and Companion both consume it through a direct Gradle project dependency. SCNet and JIndex do not gain application directory knowledge.

Feature owners still own their formats: Companion settings/profile/state, decompiler debug metadata and index metadata; game source materialization. Both applications use shared publication mechanics.

Writes use same-directory staging named `.td-<pid>-<random>`. File replacement requires an atomic move. New authored files use exclusive hard-link publication, so an existing script is never overwritten by a create operation. A filesystem without that capability reports a failure. Runtime source files are staged before replacement. Their identity is invalidated before published bytes change and committed after replacement and removal of obsolete files. An interrupted replacement is unavailable until rebuilt, never a mixture presented as valid. The single index archive is replaced atomically. Immutable executable launch directories are published only when complete. Recognized staging files are reclaimed only when their process is no longer alive, and only within their owning directory.

Script saves capture editor text on the Swing thread, use atomic replacement and do not mark a failed save successful. Tab close and application exit flush pending authored state; save failure keeps the UI open. Settings/state background writes coalesce snapshots and retain failed writes for an explicit retry/flush. Invalid persistent state fails with its path rather than being overwritten with defaults.

## Cache management

Close Minecraft and Companion before manually removing generated caches. Preserve `scripts/` and `state.json`; do not delete all of `total-debug` to refresh generated data. Missing or invalid generated caches are rebuilt from the referenced runtime sources. Missing original archives remain errors.
