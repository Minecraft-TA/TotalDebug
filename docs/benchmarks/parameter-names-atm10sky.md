# Parameter-name storage in ATM10 Sky

Measured on 2026-09-16 using the installed ATM10 Sky runtime inventory, its 544 archive sources in their recorded order, and its Java 21.0.7 runtime image. Both runs selected 180,640 classes and 1,391,144 methods. Field, reference, and literal counts were unchanged.

| Persisted JIndex snapshot | Bytes | MiB |
| --- | ---: | ---: |
| Before parameter metadata, format 6 | 87,682,842 | 83.621 |
| With parameter metadata, format 7 | 91,730,048 | 87.481 |
| Increase | 4,047,206 | 3.860 |

The increase is **4.62%**. These are the compressed snapshot files produced by JIndex, excluding Companion's source manifest and decompiled-source cache.

The corpus contains 1,691,648 parameter positions. Bytecode supplies 1,540,989 names across 784,769 methods; the remaining 150,659 positions have no stored name. Name text totals 9,600,344 UTF-8 bytes before serialization/compression. Generated display names and Parchment overrides are resolved in Companion and are not stored in JIndex.

One paired run measured index construction at 11.09 s before and 10.84 s after, saving at 1.69 s and 1.77 s, and median warm loading at 1.31 s and 1.42 s. These timings are observations from one local comparison, not a controlled performance benchmark.

## Reproduction

Run JIndex's existing `RuntimeCorpusBenchmark` with Java 21.0.7, `-Xmx4g`, the same runtime-source manifest, and either the original 2.0.0 JAR or the locally built JAR containing parameter metadata. Pass the manifest and output JSON as the two program arguments. The manifest SHA-256 for this run was `6c200496a4af0c5d1baac0c69622f96fe59a9908247c8ebc27fd22ec4ebbf95e`.

Local inputs, JSON results, and the name-count helper are under `companion/build/parameter-names-benchmark/`. That directory is ignored. The original JAR was copied from the Gradle cache before running the comparison; the changed JAR was published to Maven Local.

JIndex format 7 invalidates format 6 snapshots. Companion already rebuilds unusable index caches from their runtime inventory. Its decompiler cache key also changes because parameter names and collision handling affect generated source.
