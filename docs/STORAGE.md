# Current storage layout

Implemented storage layout for TotalDebug and Companion, 2026-09-02. Project selection and reconnect are separate work. There are no migrations, old-format readers, or automatic legacy-data cleanup.

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
- `state.json` holds watches, breakpoint definitions, mute/exception choices and the last 50 distinct evaluator inputs with imports and execution side. One instance-state owner writes the whole file. The existing runtime-signature partition for breakpoint resolution remains; re-resolving breakpoint intent across pack updates belongs to the project lifecycle work.
- The one replaceable inventory describes the Java runtime, production mode, ordered physical class sources, logical origins and module ownership. Game and Companion use the same Java record, JSON format and validator.
- The source manifest lists generated JAR names, logical origins and sizes. Only virtual sources need materialization. Physical directories and JARs are referenced in place. Filenames use the artifact or Java module name; collisions receive a numeric suffix. Runtime changes replace these files and remove obsolete generated JARs.
- `index.jindex` is the only index file. Its ZIP contains the native Zstd `index` entry first, followed by `manifest.json` with inventory identity and source-id mappings. There is no index directory, generation selector or `current.json`.
- Decompiled filenames normally use the full binary class name. The manifest records runtime/decompiler identity and any shortened or disambiguated filenames. Reserved names are escaped, case-insensitive collisions receive a numeric suffix, and long names retain their beginning and end.
- Each `.debug` file contains the binary name, source checksum, line mappings and variable names. The manifest exposes a pair only after both files are written. Readers load and verify both files under the cache lock. Unlisted files from interrupted writes are reclaimed when the store opens.
- There is one current runtime, not retained generations. Source replacement, compilation and index/bytecode reads share the runtime lock. Readers validate their expected identity and fail if the runtime changed. Replacing the runtime or decompiler clears its generated source pairs; closed services cannot publish late work, and old runtime editor/usage tabs are retired.

The current source-set fingerprint still uses origin, runtime identity and source metadata rather than hashing every installed class byte. Offline indexes still depend on referenced JARs, directories and the Java installation being present. This layout does not make an offline capture portable.

## Global application files

Default Windows home: `%LOCALAPPDATA%/TotalDebugCompanion`. The existing `--app-home` and `totaldebug.companionAppHome` overrides remain.

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
- `profile.json` remembers the current instance home, actual game directory and supported capabilities. It is not a project catalog. No placeholder `project.json`, `projects.json` or game reconnect records are created.
- The `run/companion` files coordinate the existing single Companion process. Credentials are published with user-only POSIX permissions or Windows ACLs. Lock ownership, not the existence of a lock file, determines liveness.
- Immutable launch copies retain the three most recently used builds, plus any older build still pinned by a launching or running process. Publishers and pruning share a cache lock; the launcher pins the JAR through process exit, and Companion also pins its running copy. Authored scripts and installed executables are outside this cleanup scope.
- The MCP directory is Tomcat's reconstructible work area, not an execution store.
- Each launch has a unique log directory. Java output rotates between two files, each at most 4 MiB once Companion's logger starts. Up to ten recent log directories are retained, plus any older active launch. The launcher captures JVM/bootstrap failures in the same `companion.log` before application logging starts. Those pre-application bytes are not subject to the Java logger's size limit. Per-directory leases protect active logs; root locks serialize creation and pruning.

## Installed executable and loader-owned files

The installed executable stays at:

```text
{actual-game-directory}/total-debug/companion-app/TotalDebugCompanion.jar
```

For a dev run this is beneath `run/`, even though instance-authored files use the repository workspace. A configured development JAR bypasses this installed payload. `localBundle` still produces the mod and installed Companion under `build/local-bundle`; it does not copy files into an external instance.

NeoForge configuration, Minecraft logs/options, launcher files and other mods' files remain in their original owners' directories.

## Execution lifetime

MCP jobs keep generated source, status, logs, structured values and runtime context in memory. `job_source` reads the same retained record. Completed jobs are evicted oldest-first on submission when the 256-record retention target is exceeded; active jobs are never evicted. Companion exit discards all records.

There is no `history/executions`, MCP artifact directory, stored wrapper source or per-job JSON. The evaluator's small input-recall list belongs in `state.json`. Save useful code explicitly as a script.

## Ownership and publication

The internal `storage` Gradle module in TotalDebug owns paths, atomic operations, file leases, launch/log retention and shared inventory/launch contracts. Companion consumes `com.github.minecraft_ta:totaldebug-storage` from Maven Local for coordinated development. SCNet and JIndex do not gain application directory knowledge.

Feature owners still own their formats: Companion settings/profile/state, decompiler debug metadata and index metadata; game source materialization. Both applications use shared publication mechanics.

Writes use same-directory staging named `.td-<pid>-<random>`. File replacement requires an atomic move. New authored files use exclusive hard-link publication, so an existing script is never overwritten by a create operation. A filesystem without that capability reports a failure. Runtime source files are staged before replacement. Their identity is invalidated before published bytes change and committed after replacement and removal of obsolete files. An interrupted replacement is unavailable until rebuilt, never a mixture presented as valid. The single index archive is replaced atomically. Immutable executable launch directories are published only when complete. Recognized staging files are reclaimed only when their process is no longer alive, and only within their owning directory.

Script saves capture editor text on the Swing thread, use atomic replacement and do not mark a failed save successful. Tab close and application exit flush pending authored state; save failure keeps the UI open. Settings/state background writes coalesce snapshots and retain failed writes for an explicit retry/flush. Invalid persistent state fails with its path rather than being overwritten with defaults.

## Development reset

Use matching freshly built TotalDebug and Companion binaries and restart Minecraft for the game-side change. Existing scripts/state/caches from the old layout are not moved, read or deleted. Keep any scripts or execution evidence you want before manually cleaning old folders. Do not delete all of `total-debug` merely to refresh generated data.

This revision has not been deployed to or cleaned any real instance. The storage-only change does not solve the full cross-project UI/session switching lifecycle.

## Verification

The full suites pass: TotalDebug 130 tests, shared storage 12, Companion 389. Replacement tests cover removed JARs/classes, one index path across runtime changes, stale readers, case-insensitive filename collisions, incomplete source/debug publication, queued decompiler cancellation, and selective editor disposal. Unknown cache directories are rejected without migration or deletion.

`localBundle` builds both artifacts. An isolated headless startup check loads the packaged Companion and shared storage classes, verifies launch pinning, and checks that failed startup removes its published credentials. This is packaging verification, not a live Minecraft test of this revision.
