# Profiling runtime preparation

Both Minecraft and Companion emit `com.github.minecraft_ta.totaldebug.RuntimePhase` events while Java Flight Recorder is recording. No additional Java agent is required.

Record each process separately:

```shell
jcmd <pid> JFR.start settings=profile filename=runtime.jfr dumponexit=true
```

Start the Minecraft recording before pressing F6 to include source collection and the handoff. Start Companion's recording at JVM launch to include its startup. Use distinct output filenames for each process and run.

Events cover discovery, collection, source identity checks, packing, Companion startup, index requests, JDK inputs, native build/save/load, cache validation and publication, installation, and shutdown services. `-Dtotaldebug.profileTimings=true` also writes durations to the process's diagnostic output.

Compare cold runs with an empty generated cache separately from warm runs that reuse it. Keep the modpack, Java runtime and input contents fixed. Native index construction is only one part of startup; it excludes game-side collection, JVM startup and cache publication.

Shutdown spans separate debugger, MCP, transport, query services, native disposal, UI cleanup and state saves. Native JIndex build/load operations cannot be interrupted while in progress. Cancellation prevents later phases and installation, and disposal waits for native ownership to be released.
