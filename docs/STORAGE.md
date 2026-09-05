# Storage and caches

TotalDebug keeps instance-authored files with the Minecraft workspace and application settings in Companion's global home. Generated caches are replaceable; scripts and debugger state are user data.

## Instance files

The instance home is `{workspace}/total-debug`. A normal modpack uses the actual Minecraft game directory as its workspace. The checked-in development runs explicitly set `totaldebug.workspaceRoot` to the TotalDebug repository. They do not infer a project by walking parents or looking for Gradle files.

```text
total-debug/
  scripts/
    Example.tdscript
  state.json
  cache/
    runtime/
      .lock
      inventory.json
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
```

Files are created when needed; an empty instance does not need every directory.

- `scripts` contains authored methodless Java scripts.
- `state.json` holds watches, breakpoint definitions, mute/exception choices and the last 50 distinct evaluator inputs with imports and execution side. One instance-state owner writes the whole file. Breakpoint resolution is partitioned by runtime signature.
- The one replaceable inventory describes the Java runtime, production mode, ordered physical class sources, logical origins and module ownership. Game and Companion use the same Java record, JSON format and validator.
- The format-2 source manifest lists generated JAR names, effective-content fingerprints, sizes and output SHA-256 hashes. Physical directories and JARs are referenced in place. A virtual root reuses its original archive only after its class entries and manifest match the effective loader view. Nested archives are copied as bytes; filtered or merged views are packed with buffered, compressed ZIP output. Filenames use the artifact or Java module name; collisions receive a numeric suffix. Only changed or damaged files are regenerated, and obsolete generated JARs are removed.
- `index.jindex` is the only index file. Its ZIP contains the native Zstd `index` entry first, followed by `manifest.json` with inventory identity and source-id mappings. There is no index directory, generation selector or `current.json`.
- Decompiled filenames normally use the full binary class name. The manifest records runtime/decompiler identity and any shortened or disambiguated filenames. Reserved names are escaped, case-insensitive collisions receive a numeric suffix, and long names retain their beginning and end.
- Each `.debug` file contains the binary name, source checksum, line mappings and variable names. The manifest exposes a pair only after both files are written. Readers load and verify both files under the cache lock. Unlisted files from interrupted writes are reclaimed when the store opens.
- There is one current runtime, not retained generations. Source replacement, compilation and index/bytecode reads share the runtime lock. Readers validate their expected identity and fail if the runtime changed. Replacing the runtime or decompiler clears its generated source pairs; closed services cannot publish late work, and old runtime editor/usage tabs are retired.

Source identities hash effective class contents and manifest bytes for directories, and archive bytes for direct archive inputs. The aggregate includes ordered physical representations and module names, without loader filesystem creation counters. Proven duplicate archives retain the first source's ownership and precedence. Offline indexes still depend on referenced JARs, directories and the Java installation being present. This layout does not make an offline capture portable.

Cache reuse is optional. Startup and live inventory handling validate the current inventory first, then reuse a matching readable index or rebuild it once in place. Missing, invalid or unsupported generated source manifests and indexes are recreated, not migrated. Minecraft retains the discovered original source paths, but revalidates their generated copies when requested. Missing original runtime sources and failed rebuilds remain errors; authored files are never part of cache recovery.

Matching live inventory announcements join a pending restore. A cold build retains the native index loaded during staged-file validation and installs it after atomic publication. Closing or superseding a request cancels later phases and discards late results. Native operations keep ownership until they return; installed-index disposal still waits for active queries.

## Global application files

Default Windows home: `%LOCALAPPDATA%/TotalDebugCompanion`. Override it with `--app-home` or `totaldebug.companionAppHome`.

```text
TotalDebugCompanion/
  settings.json
  profile.json
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
- `profile.json` remembers the current instance home and actual game directory. Companion reopens this profile on standalone startup.
- The `run/companion` files coordinate the existing single Companion process. Credentials are published with user-only POSIX permissions or Windows ACLs. Lock ownership, not the existence of a lock file, determines liveness.
- Immutable launch copies retain the three most recently used builds, plus any older build still pinned by a launching or running process. Publishers and pruning share a cache lock; the launcher pins the JAR through process exit, and Companion also pins its running copy. Authored scripts and installed executables are outside this cleanup scope.
- The MCP directory is Tomcat's reconstructible work area, not an execution store.
- Each launch has a unique log directory. Java output rotates between two files, each at most 4 MiB once Companion's logger starts. Up to ten recent log directories are retained, plus any older active launch. The launcher captures JVM/bootstrap failures in the same `companion.log` before application logging starts. Those pre-application bytes are not subject to the Java logger's size limit. Per-directory leases protect active logs; root locks serialize creation and pruning.

## Installed executable and loader-owned files

The installed executable stays at:

```text
{actual-game-directory}/total-debug/companion-app/TotalDebugCompanion.jar
```

TotalDebug downloads and verifies its paired Companion release only when this JAR is missing. Existing files are preserved, including manual development replacements. Updating to a new application pair requires replacing or removing the installed JAR explicitly.

For a dev run this is beneath `run/`, even though instance-authored files use the repository workspace. A configured development JAR bypasses this installed payload. `localBundle` produces a flat pair of JARs under `build/local-bundle`. `deployLocal` installs them into the explicitly configured Minecraft directory and points its client configuration at the mutable Companion build. See the README deployment instructions.

NeoForge configuration, Minecraft logs/options, launcher files and other mods' files remain in their original owners' directories.

## Execution lifetime

MCP jobs keep generated source, status, logs, structured values and runtime context in memory. `job_source` reads the same retained record. Completed jobs are evicted oldest-first on submission when the 256-record retention target is exceeded; active jobs are never evicted. Companion exit discards all records.

The evaluator's input-recall list belongs in `state.json`. Save useful code explicitly as a script.

## Ownership and publication

The internal `storage` Gradle module in TotalDebug owns paths, atomic operations, file leases, launch/log retention and shared inventory/launch contracts. Companion consumes `com.github.minecraft_ta:totaldebug-storage` from Maven Local for coordinated development. SCNet and JIndex do not gain application directory knowledge.

Feature owners still own their formats: Companion settings/profile/state, decompiler debug metadata and index metadata; game source materialization. Both applications use shared publication mechanics.

Writes use same-directory staging named `.td-<pid>-<random>`. File replacement requires an atomic move. New authored files use exclusive hard-link publication, so an existing script is never overwritten by a create operation. A filesystem without that capability reports a failure. Runtime source files are staged before replacement. Their identity is invalidated before published bytes change and committed after replacement and removal of obsolete files. An interrupted replacement is unavailable until rebuilt, never a mixture presented as valid. The single index archive is replaced atomically. Immutable executable launch directories are published only when complete. Recognized staging files are reclaimed only when their process is no longer alive, and only within their owning directory.

Script saves capture editor text on the Swing thread, use atomic replacement and do not mark a failed save successful. Tab close and application exit flush pending authored state; save failure keeps the UI open. Settings/state background writes coalesce snapshots and retain failed writes for an explicit retry/flush. Invalid persistent state fails with its path rather than being overwritten with defaults.

## Cache management

Close Minecraft and Companion before manually removing generated caches. Preserve `scripts/` and `state.json`; do not delete all of `total-debug` to refresh generated data. Missing or invalid generated caches are rebuilt from the referenced runtime sources. Missing original archives remain errors.
