# Complete current statement

Status: implemented, with planner and Swing integration tests. Postfix templates are committed as `1ebd02c5` on `codex/postfix-templates`.

Ctrl+Shift+Enter completes the statement containing the caret. It works independently of autocomplete, including when the caret is in the middle of a multiline statement. The user does not need to select a completion or move to the end first.

## Behavior

`|` marks the caret in these examples.

| Before | Result |
| --- | --- |
| `logln("hel\|lo")` | `logln("hello");` followed by an indented new line with the caret |
| `logln("hello"\|` | Close the call, add `;`, and start the next line |
| `int count = 1\|0` | `int count = 10;` followed by the next line |
| `return sta\|ck` | `return stack;` followed by the next line |
| `if (stack.isEmpty()\|` | Close the condition, add a block, and place the caret inside it |
| `if\|` | Create `if () { ... }`, leaving the caret in the empty condition |
| `int count = \|` | Keep the caret at the missing initializer instead of treating the statement as finished |
| `call(); // note` with the caret in `call` | Preserve the comment and move to the next line without adding another semicolon |

The first implementation covers expression statements, local declarations, imports, return/throw expressions, and ordinary `if`/`else`, `while`, and classic/enhanced `for` constructs. Missing conditions, operands, and required header parts remain input positions. Explicitly empty clauses in `for (;;)` are valid.

Finish syntax only. Do not invent expressions, argument values, method names, or types. An unresolved name does not prevent closing an otherwise clear statement. Missing method arguments are semantic errors, not something this syntax command must infer from overloads.

Reuse existing delimiters and bodies. Do not attach the next independent statement to a malformed construct merely because parser recovery suggests it. If the intended boundary is ambiguous, leave the source intact and keep or position the caret at the missing input. Repeating the command at an unfilled input must not add more braces or punctuation.

An open autocomplete popup closes without accepting its selected suggestion. Pending suggestion requests are cancelled. An active snippet ends linked editing while preserving its current text and caret, then the command operates on that text. Ordinary Enter and Tab retain their current behavior.

On a blank line, preserve the existing indentation and caret. Inside comments, leave the source unchanged. A caret inside a closed string or text block still targets its containing statement; literal contents remain unchanged. An unfinished literal is not repaired. A nonempty selection is not replaced; use its active caret endpoint to identify the statement.

## Structure

Keep one syntax planner and use the existing editor owner to run and apply it.

- Add a pure `StatementCompletion` planner taking an immutable source snapshot and caret offset. Return targeted text edits and the final caret or input selection. Use JDT tokens and a recovered syntax tree without binding resolution. This must work without a runtime index or game connection.
- Add the shortcut to [ScriptCompletionController](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/editors/ScriptCompletionController.java). Capture the source and caret on the EDT, plan on the existing executor, and apply only if the request, document, caret, focus, and editor lifetime are still current. Reuse cancellation and atomic-edit ownership rather than introducing another editor coordinator.
- Restrict planned edits to editor source. [JavaSnippetSource](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/jdt/JavaSnippetSource.java) adds synthetic wrapper methods and closing braces; none may count as user-written delimiters or repair targets.
- Make popup Enter handling modifier-aware so it cannot consume Ctrl+Shift+Enter before the editor action runs. No accept-and-complete request mode or virtual composition of completion proposals is needed for this scope.
- Apply edits and caret movement in one EDT transaction and one undo step. Never leave an undo transaction open while background work runs. Use targeted edits, not a full-document replacement.
- Reuse the existing analysis notification after applying the command. [JavaEditorAnalysis](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/ui/components/editors/JavaEditorAnalysis.java) must retain unrelated diagnostics and highlighting. An unresolved input position stays under the existing active-edit policy; do not globally finish editing or add a timeout.

The planner identifies the smallest supported containing statement, determines its missing syntax, and produces its result in memory. Any recovery passes must be bounded and restricted to that construct. Use direct helpers for the supported constructs; no extensible registry of fixers.

Indent only inserted lines and the repaired construct where necessary. Respect the editor's indentation settings and line endings. Do not invoke ordinary Enter as a final step: its current [CustomJavaTokenMaker](../companion/src/main/java/com/github/minecraft_ta/totalDebugCompanion/jdt/semanticHighlighting/CustomJavaTokenMaker.java) implementation counts unmatched braces across the document and could close an unrelated block.

## Implementation sequence

1. Establish planner tests for statement selection, missing call delimiters, semicolons, comments, existing punctuation, and caret placement. Implement these basic statements first.
2. Add control headers and bodies, including empty required inputs, existing blocks, nested constructs, multiline conditions, and valid empty `for` clauses.
3. Wire the shortcut, cancellation, popup dismissal, snippet termination, atomic edits, and analysis notification. Test the actual Swing key path rather than only invoking planner methods.
4. Replay representative scripts, run the Companion test suite, and audit the diff for duplicate parsing, offset mistakes, and unnecessary infrastructure.

Complex repairs for `switch`, `try`/`catch`/`finally`, `do`/`while`, and method/class declarations are outside this first slice. Such constructs may surround supported statements, so targeting must still work inside their bodies. Do not silently claim full IntelliJ parity.

## Verification

Planner tests must cover caret positions at the beginning, middle, and end; multiple statements on one line; multiline calls; nested calls; casts and generics; array expressions; comments containing braces; CRLF; tabs and spaces; trailing comments; existing closing delimiters; and unrelated malformed code before and after the target. Include chained Minecraft calls and casts of `NeoForge.EVENT_BUS` in the replay.

Explicitly test incomplete operands, empty conditions, trailing commas, partial member access, nested control blocks, dangling `else`, and repeated invocation. Assert the whole resulting source and exact caret, including preservation of neighboring code and imports.

Swing tests must cover popup visible/hidden, a pending completion request, an active snippet, one-step undo/redo, typing or moving the caret while planning, Escape, focus loss, disposal, and a read-only editor. A rejected stale result must make no edits and must not move the caret.

Observe diagnostics during the edit sequence, not only after analysis settles. Existing warnings and semantic colors must survive the command without an intermediate cleared frame. Missing-input positions must not briefly display provisional errors before receiving focus.

## Reference

[JetBrains documentation](https://www.jetbrains.com/help/idea/advanced-code-completion.html) describes completing punctuation and moving to the next editing position. Its [Java smart-enter processor](https://github.com/JetBrains/intellij-community/blob/master/java/java-frontback-impl/src/com/intellij/codeInsight/editorActions/smartEnter/JavaSmartEnterProcessor.java) uses syntax-specific repairs and tracks the first unresolved input. Use those behaviors as reference while keeping our script-focused implementation smaller.
