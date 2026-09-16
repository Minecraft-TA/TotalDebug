# Subtype completion checks on ATM10 Sky

Measured on 2026-09-16 with Java 21, the saved ATM10 Sky index from the
[parameter-name benchmark](parameter-names-atm10sky.md), and the production completion pipeline.
The index contains 544 archive sources and the JDK. These checks did not execute game code.

Each case ran six times in one JVM. The table reports the last five runs; the first event-bus
request also initialized JDT and took 534 ms. Timings include snippet generation, compilation-unit
creation, JDT completion, subtype lookup, ranking, and edit preparation. They exclude index loading
and Swing rendering. These are local observations, not latency guarantees.

| Receiver and typed member | Expected result observed | Warm time, ms |
|---|---|---|
| `NeoForge.EVENT_BUS.listeners` | Private field, cast to `EventBus`, normal import | 22–38 |
| `NeoForge.EVENT_BUS.getListenerL()` | Method with cast; existing parentheses retained | 18–19 |
| `List<String>.elementDa` | Fields and methods from `ArrayList<String>` and `Vector<String>` | 65–88 |
| `List<String>.ensureCapa` | `ensureCapacity` on both implementations; generic arguments retained | 49–84 |
| `ItemStack.coun` | Existing direct field/method suggestions, no cast | 25–30 |
| `Level.entityMan` | Private `entityManager`, cast to `ServerLevel` | 72–92 |
| `Object.listen` | No subtype expansion | 3 |

Accepting an existing empty call preserves it; it does not invent argument values. For example,
`getListenerList()` still needs its class argument after completion.

## Regression and visual checks

The targeted Companion tests cover completion ranking and presentation, parameter names and hints,
subtype casts, popup interaction, and the expression-editor adapter. Cases include alternate
implementations, inaccessible classes, inherited default methods, generic bounds and wildcards,
local type-name conflicts, `instanceof` proposals, existing arguments and comments, cancellation,
and atomic undo. All 68 targeted tests passed after the audit fixes.

An earlier full Companion run passed 700 of 701 tests. The unchanged
`FlatIconButtonTest.bothThemesPaintDistinctKeyboardFocusAndSelectedStates` failed its focus-paint
assertion; it also failed intermittently in focused reruns. This was not reported as a clean full run.

The `completion-casts`, `completion-modifiers`, and `signature-help` UI scenarios were inspected
through the offscreen harness in light and dark themes during this pass. Cast labels occupy a
separate column so truncating long argument lists does not remove the required cast indication.
