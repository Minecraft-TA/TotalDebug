# Runtime preparation and Companion indexing

Implementation and offline verification, 2026-09-05, using the captured ATM10 Sky runtime.

The profiled cold startup reached index readiness in 49.25 seconds. Collection took 24.83 seconds, including 22.93 seconds repacking 162 virtual roots. Discovery itself took 0.45 seconds. The JIndex audit's roughly 9-second measurement covered native construction and excluded collection, JVM startup, JDK preparation and cache publication.

## Changes

- Buffer generated ZIP output at 64 KiB and keep default compression enabled.
- Compare the effective loader class entries and manifest with a candidate original archive before reusing it. Copy equivalent nested archives directly. Keep materializing filtered and merged views when equivalence cannot be proved.
- Deduplicate proven physical representations in discovery order, retaining the first source's module ownership.
- Store per-source content fingerprints and generated-file hashes. Regenerate only changed or damaged outputs. Provider creation counters do not enter the aggregate identity.
- Join matching in-flight restore/live requests. Preserve the installed services during a preparation announcement until the new identity is known.
- Adopt the native index produced by staged-file validation after atomic publication, eliminating the second cold deserialization.
- Cancel later index phases after close or supersession. Discard late results and retain native ownership until an operation finishes.

## Captured-corpus replay

All figures below are offline runs, not a deployed end-to-end latency claim. Minecraft remained usable. The replay restored 154 original loader views after comparing them with the captured class contents; eight unmatched views used their captured effective contents. The 21 other virtual archives were replayed as virtual files from their captured bytes.

| Measurement | Result |
|---|---:|
| Repack all 162 roots, buffered default compression | 5.73 s, 70.49 MB |
| Repack all 162 roots, compression disabled | 1.74 s, 168.14 MB |
| New complete source preparation, empty generated cache | 4.49 s |
| New source preparation, populated cache | 3.35 s |
| Published archive sources | 612 → 538 |
| Newly generated files in replay | 107, 48.12 MB |

Compression remains enabled by user preference. Each compression variant preserved every retained entry's name and SHA-256 hash. Source reuse preserved native index statistics: 178,115 classes, 605,322 fields, 1,299,691 methods, 14,880,073 reference sites, 609,249 literals and 2,047,529 literal occurrences. The complete optimized replay built natively in 13.27 seconds; an earlier replay measured 12.26 seconds. There is no established native indexing speedup. A concurrent-build run took 17.08 seconds and is unsuitable for latency comparison.

Focused tests cover matching mid-load announcements, superseding instances, close during native completion, retained validation objects, filtered and merged roots, nested archive bytes, stable provider identities, changed-source isolation, damaged outputs, and same-size source changes with preserved timestamps. The original matching-load and selective-regeneration regressions were observed failing before the fixes.

Final coordinated wrapper builds passed: 420 TotalDebug tests, 13 shared-storage tests and 426 Companion tests. Shared dependencies were published to Maven Local before the Companion build. The existing evaluation and UI changes were included in these checks; the missing dark variants for the new editor icons were corrected.

## Recording normal runs

Both JVMs emit `com.github.minecraft_ta.totaldebug.RuntimePhase` events when JFR is recording. Standard JFR startup or `jcmd <pid> JFR.start settings=profile filename=<file> dumponexit=true` records them without a Java agent. Start the Companion recording at JVM launch to include its initial startup. Start the Minecraft recording before pressing F6 to include collection and handoff. Use distinct output filenames for each JVM and run.

The events cover discovery, collection, source identity checks, packing, Companion startup, index requests, JDK inputs, native build/save/load, cache validation/publication, installation, and shutdown services. `-Dtotaldebug.profileTimings=true` additionally writes durations to that JVM's diagnostic output. Instrumentation never schedules closure or changes launch arguments.

Idle shutdown previously measured 0.83–0.87 seconds including JFR dump-on-exit; the intermittent slow close was not reproduced. Shutdown spans now separate debugger, MCP, transport, query services, native disposal, UI cleanup and state saves. Native build/load remains non-interruptible inside JIndex; cancellation prevents subsequent work and installation, rather than freeing memory still in use. A normal deployed cold/warm/close comparison remains outstanding.
