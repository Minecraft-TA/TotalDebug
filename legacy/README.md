# Legacy reference sources

These local trees are preserved for search, comparison, and behavioral reference. Git does not track their `src` directories, and Gradle does not compile or package them.

## 1.7.10

`1.7.10/src` comes from the archived pre-port working tree at commit `a471525`. It contains work that was never fully represented by the historical `1.7.10` branch.

This is the more advanced TotalDebug implementation for most features, including several parts of reference search, runtime transformation, compilation, and companion integration. Treat it as the primary functional reference unless a subsystem clearly exists only in 1.12.2.

## 1.12.2

`1.12.2/src` comes from the clean `master` branch at commit `6db445c`. It is the better reference for later Minecraft and Forge APIs, but it is not assumed to contain the newest TotalDebug behavior.

Do not copy either tree wholesale into `src/main`. Port one feature at a time and verify its intended behavior against both snapshots.
