package com.github.minecraft_ta.totalDebugCompanion.inspection;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionTextDisplay;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptCompilationService;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptSubject;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService.Side;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionValue;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * One block or entity selected in the game, and its reads. A read runs the built-in readers against {@code target()}
 * on the chosen side through the ordinary snippet path, so it sees exactly what a script bound to the same subject
 * sees, together with every project tool whose {@code // inspect:} patterns match what occupies the subject; each
 * tool is its own run, so a failing tool leaves the others alone. The session follows what occupies the subject: a
 * read reporting another registry id marks the subject replaced and selects the tools again. While live, a read
 * starts again once the previous one, tools included, has finished and the page is visible. A read that fails keeps
 * the facts it did report, and a failed read keeps the previous one, marked outdated.
 * <p>
 * The session publishes its {@link State} after every change. It is used on the event dispatch thread.
 */
public final class InspectionSession {
    public static final List<Integer> LIVE_INTERVALS_MS = List.of(500, 1_000, 2_000, 5_000);
    /** The snippet a read runs: the built-in readers report sections through every face, and the target is the result. */
    private static final String READER_SOURCE = """
            import com.github.minecraft_ta.totaldebug.inspection.InspectionReaders;
            InspectionReaders.read(target(), facts());
            return target();
            """;

    /** How the latest built-in read went. */
    public enum Outcome {
        /** No read has finished yet. */
        NONE,
        /** The chosen side cannot run a read yet; the read starts once it can. */
        WAITING,
        COMPLETED,
        /** The read finished with some parts missing, or stopped after reporting some facts. */
        PARTIAL,
        /** The latest read failed; the previous read is still shown. */
        OUTDATED,
        /** The read failed and there is no earlier read to show. */
        FAILED
    }

    /** The built-in read: its sections, the object it returned, and a problem when it did not complete cleanly. */
    public record Read(Outcome outcome, List<FactSection> sections, ExecutionValue value, String problem, String details) {
        public Read {
            sections = List.copyOf(sections);
        }

        static Read none() {
            return new Read(Outcome.NONE, List.of(), null, "", "");
        }

        /** Whether facts of an earlier or partial read are on hand. */
        public boolean hasFacts() {
            return this.outcome == Outcome.COMPLETED || this.outcome == Outcome.PARTIAL || this.outcome == Outcome.OUTDATED;
        }
    }

    /** One tool's latest run; {@code failure} is empty unless the run failed. */
    public record ToolRead(InspectionTool tool, boolean running, List<FactSection> sections, String output, String failure) {
        public ToolRead {
            sections = List.copyOf(sections);
        }

        ToolRead started() {
            return new ToolRead(this.tool, true, this.sections, this.output, this.failure);
        }
    }

    /**
     * What the inspection shows now. {@code replaced} is what occupied the subject before a read found something
     * else there, or null; {@code toolsProblem} is why the tools could not be loaded, or empty.
     */
    public record State(SubjectIdentity identity, SubjectIdentity replaced, Read read, List<ToolRead> tools,
                        String toolsProblem, boolean reading, boolean live) {
        public State {
            tools = List.copyOf(tools);
        }
    }

    private final InspectSubjectPayload subject;
    private final Supplier<SnippetExecutionService> snippets;
    private final Supplier<ScriptFiles> scripts;
    private final Consumer<State> listener;
    private final BooleanSupplier visible;
    private final Timer liveTimer = new Timer(1_000, event -> liveTick());
    private final Set<Path> chosen = new LinkedHashSet<>();
    private final List<SnippetExecutionService.Execution> activeTools = new ArrayList<>();
    private Map<Path, ToolRead> toolReads = new LinkedHashMap<>();
    private List<InspectionTool> tools = List.of();
    private String toolsProblem = "";
    private SubjectIdentity identity;
    private SubjectIdentity replaced;
    private Read read = Read.none();
    private Side side;
    private boolean live;
    private int liveInterval = 1_000;
    private SnippetExecutionService.Execution active;
    private long revision;
    private long toolRevision;
    private CompletableFuture<Void> toolRun = CompletableFuture.completedFuture(null);
    private boolean disposed;

    /** {@code visible} tells whether the page shows, so live reads pause while it is hidden. */
    public InspectionSession(InspectSubjectPayload subject, Supplier<SnippetExecutionService> snippets,
                             Supplier<ScriptFiles> scripts, Consumer<State> listener, BooleanSupplier visible) {
        this.subject = Objects.requireNonNull(subject, "subject");
        this.snippets = Objects.requireNonNull(snippets, "snippets");
        this.scripts = Objects.requireNonNull(scripts, "scripts");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.visible = Objects.requireNonNull(visible, "visible");
        this.identity = subject.identity();
        this.side = snapshot() ? Side.CLIENT : Side.SERVER;
        this.liveTimer.setRepeats(false);
    }

    public State state() {
        return new State(this.identity, this.replaced, this.read, List.copyOf(this.toolReads.values()),
                this.toolsProblem, this.active != null, this.live);
    }

    /** The project's tools as last loaded, for choosing more of them. */
    public List<InspectionTool> tools() {
        return this.tools;
    }

    /** Whether project tools run on this subject: blocks and entities, not stacks. */
    public boolean readsTools() {
        return !snapshot();
    }

    /**
     * Whether the subject is a stack the client kept as it was when selected: it is read on the client, and reading
     * it again shows the same stack.
     */
    public boolean snapshot() {
        return SubjectRef.parseOccurrence(this.subject.subject()) instanceof SubjectRef.Stack;
    }

    public boolean chosen(Path tool) {
        return this.chosen.contains(tool);
    }

    /** Runs {@code tool} in this inspection even though its patterns do not match, or stops doing so; then reads. */
    public void choose(Path tool, boolean run) {
        if (run) this.chosen.add(tool);
        else this.chosen.remove(tool);
        refresh();
    }

    /** Reads from the server's or the client's copy of the world from now on. */
    public void setSide(Side side) {
        if (this.side == Objects.requireNonNull(side, "side")) return;
        this.side = side;
        refresh();
    }

    /** Reads again automatically, {@code intervalMs} after each read finishes, while the page is visible. */
    public void setLive(boolean live, int intervalMs) {
        this.liveInterval = intervalMs;
        if (this.live == live) return;
        this.live = live;
        if (live) {
            refresh();
        } else {
            this.liveTimer.stop();
            publish();
        }
    }

    /** Starts a new read of the subject, replacing one still running. */
    public void refresh() {
        requireEdt();
        if (this.disposed) return;
        this.liveTimer.stop();
        cancelActive();
        Side selected = this.side;
        JavaSnippetSource.GeneratedSource source = JavaSnippetSource.body("InspectTarget", READER_SOURCE);
        long current = ++this.revision;
        if (!awaitReadiness(current, selected)) return;
        try {
            this.active = this.snippets.get().execute(source, selected, ScriptExecutionEnvironment.POST_TICK,
                    new ScriptSubject(SubjectRef.parseOccurrence(this.subject.subject()), this.subject.gameSessionId()));
        } catch (RuntimeException exception) {
            readFailed(exception.getMessage());
            scheduleLive(current);
            return;
        }
        publish();
        runTools(selected);
        CompletableFuture<Void> presented = new CompletableFuture<>();
        this.active.completion().whenComplete((outcome, failure) -> SwingUtilities.invokeLater(() -> {
            try {
                if (this.disposed || current != this.revision) return;
                this.active = null;
                present(outcome, failure, source::mapDiagnostics);
            } finally {
                presented.complete(null);
            }
        }));
        // A replaced subject starts its tools again while presenting; the next read waits for the run current then.
        presented.thenCompose(ignored -> this.toolRun).whenComplete((ignored, failure) ->
                SwingUtilities.invokeLater(() -> scheduleLive(current)));
    }

    /**
     * Takes a finished built-in read: its identity, then its facts, even when the read failed part way. A read
     * without facts leaves the previous one on hand. {@code diagnostics} maps error positions to the read's source.
     */
    public void present(ExecutionResult outcome, Throwable failure, UnaryOperator<String> diagnostics) {
        if (failure != null) {
            readFailed(failure.getMessage());
            return;
        }
        if (outcome.identity() != null) applyIdentity(outcome.identity());
        boolean completed = outcome.status() == ExecutionStatus.RUN_COMPLETED && outcome.value() != null;
        String error = ExecutionTextDisplay.format(outcome.error());
        error = completed ? "" : error.isBlank() ? "The read ended without a result" : diagnostics.apply(error);
        if (!completed && outcome.facts().isEmpty()) {
            readFailed(error);
            return;
        }
        String logs = ExecutionTextDisplay.format(outcome.logs()).strip();
        ExecutionValue value = outcome.value() != null ? outcome.value() : this.read.value();
        if (!completed) {
            this.read = new Read(Outcome.PARTIAL, outcome.facts(), value, "The read stopped early: " + firstLine(error),
                    error + "\n\n" + logs);
        } else if (hasProblems(outcome.facts())) {
            this.read = new Read(Outcome.PARTIAL, outcome.facts(), value, "Some parts could not be read", logs);
        } else {
            this.read = new Read(Outcome.COMPLETED, outcome.facts(), value, "", "");
        }
        publish();
    }

    /**
     * Creates a tool script for what occupies the subject now, under the scripts' tools folder, and reads again so it
     * runs. Blocking work runs off the event thread; the result completes with the script's path.
     */
    public CompletableFuture<Path> createTool(String name) {
        SubjectIdentity current = this.identity;
        String title = current.displayName().isBlank() ? name : current.displayName();
        return CompletableFuture.supplyAsync(() -> {
            try {
                ScriptFiles files = this.scripts.get();
                Path folder = files.root().resolve(InspectionTool.FOLDER);
                if (!Files.isDirectory(folder)) {
                    folder = files.create(files.root(), InspectionTool.FOLDER, true, "");
                }
                return files.create(folder, name, false, InspectionTool.template(current.registryId(), title));
            } catch (Exception exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
        }).whenComplete((path, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure == null) refresh();
        }));
    }

    public void dispose() {
        requireEdt();
        this.disposed = true;
        this.liveTimer.stop();
        cancelActive();
        cancelTools();
    }

    /** The innermost message of {@code failure}, or its type when it has none. */
    public static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName());
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse(text);
    }

    /**
     * A page opened from the game usually exists before the runtime index and the server comparison are ready. Until
     * the chosen side can run a read, the state says why, and the read starts as soon as that changes. Returns
     * whether the read can start now.
     */
    private boolean awaitReadiness(long current, Side selected) {
        ScriptCompilationService.Readiness readiness;
        try {
            readiness = this.snippets.get().readiness(selected);
        } catch (RuntimeException unavailable) {
            return true;
        }
        if (readiness.ready()) return true;
        if (this.read.hasFacts()) {
            readFailed(readiness.detail());
        } else {
            this.read = new Read(Outcome.WAITING, List.of(), null, readiness.detail(), readiness.detail());
            publish();
        }
        readiness.changed().thenRunAsync(() -> {
            if (!this.disposed && current == this.revision) refresh();
        }, SwingUtilities::invokeLater);
        return false;
    }

    /** A read that produced nothing to show: the previous read stays, marked outdated, or the problem replaces it. */
    private void readFailed(String message) {
        String text = message == null || message.isBlank() ? "The read failed" : message;
        this.read = this.read.hasFacts()
                ? new Read(Outcome.OUTDATED, this.read.sections(), this.read.value(),
                        "Showing the previous read. This one failed: " + firstLine(text), text)
                : new Read(Outcome.FAILED, List.of(), null, text, text);
        publish();
    }

    /**
     * Takes the identity a read reported. Another registry id means the subject was replaced; the tools are selected
     * again for what is there now.
     */
    private void applyIdentity(SubjectIdentity reported) {
        if (reported.equals(this.identity)) return;
        boolean replacedNow = !reported.registryId().equals(this.identity.registryId());
        if (replacedNow) this.replaced = this.identity;
        this.identity = reported;
        if (replacedNow) runTools(this.side);
    }

    /**
     * Loads the project's tools and runs those that apply, replacing earlier runs. Tools that ran before keep their
     * last read until the new one finishes. The result, also kept as the current tool run, completes when every tool
     * has finished. Tools read blocks and entities; a stack runs none.
     */
    private CompletableFuture<Void> runTools(Side selected) {
        cancelTools();
        long current = ++this.toolRevision;
        CompletableFuture<Void> done = new CompletableFuture<>();
        this.toolRun = done;
        if (!readsTools()) {
            done.complete(null);
            return done;
        }
        CompletableFuture.supplyAsync(this::loadTools).whenComplete((loaded, failure) -> SwingUtilities.invokeLater(() -> {
            if (this.disposed || current != this.toolRevision) {
                done.complete(null);
                return;
            }
            if (failure != null) {
                this.toolsProblem = "Tools could not be read: " + rootMessage(failure);
                this.toolReads = new LinkedHashMap<>();
                publish();
                done.complete(null);
                return;
            }
            this.toolsProblem = "";
            this.tools = loaded;
            String registryId = this.identity.registryId();
            Map<Path, ToolRead> next = new LinkedHashMap<>();
            for (InspectionTool tool : loaded) {
                if (!tool.appliesTo(registryId) && !this.chosen.contains(tool.path())) continue;
                ToolRead before = this.toolReads.get(tool.path());
                next.put(tool.path(), before == null ? new ToolRead(tool, true, List.of(), "", "") : before.started());
            }
            this.toolReads = next;
            publish();
            List<CompletableFuture<?>> runs = new ArrayList<>();
            for (ToolRead tool : next.values()) runs.add(startTool(tool.tool(), selected, registryId, current));
            CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> done.complete(null));
        }));
        return done;
    }

    private List<InspectionTool> loadTools() {
        try {
            return InspectionTool.load(this.scripts.get());
        } catch (Exception exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private CompletableFuture<?> startTool(InspectionTool tool, Side selected, String registryId, long current) {
        JavaSnippetSource.GeneratedSource source;
        SnippetExecutionService.Execution execution;
        try {
            source = JavaSnippetSource.body(tool.name(), tool.text());
            source.requireExecutableSize();
            execution = this.snippets.get().execute(source, selected, ScriptExecutionEnvironment.POST_TICK,
                    new ScriptSubject(SubjectRef.parseOccurrence(this.subject.subject()), this.subject.gameSessionId(), registryId));
        } catch (RuntimeException exception) {
            finishTool(tool, List.of(), "", failureText(exception.getMessage(), "The tool failed"));
            return CompletableFuture.completedFuture(null);
        }
        this.activeTools.add(execution);
        return execution.completion().handle((outcome, failure) -> {
            SwingUtilities.invokeLater(() -> {
                this.activeTools.remove(execution);
                if (this.disposed || current != this.toolRevision) return;
                if (failure != null) {
                    finishTool(tool, List.of(), "", failureText(failure.getMessage(), "The tool failed"));
                } else if (outcome.status() != ExecutionStatus.RUN_COMPLETED) {
                    String error = ExecutionTextDisplay.format(outcome.error());
                    finishTool(tool, List.of(), "", error.isBlank() ? "The tool ended without a result" : source.mapDiagnostics(error));
                } else {
                    finishTool(tool, outcome.facts(), ExecutionTextDisplay.format(outcome.logs()).strip(), "");
                }
            });
            return null;
        });
    }

    private void finishTool(InspectionTool tool, List<FactSection> sections, String output, String failure) {
        if (!this.toolReads.containsKey(tool.path())) return;
        this.toolReads.put(tool.path(), new ToolRead(tool, false, sections, output, failure));
        publish();
    }

    /** Queues the next live read once the current one, tools included, has finished. */
    private void scheduleLive(long current) {
        if (this.disposed || current != this.revision || !this.live) return;
        this.liveTimer.setInitialDelay(this.liveInterval);
        this.liveTimer.restart();
    }

    private void liveTick() {
        if (this.disposed || !this.live) return;
        if (!this.visible.getAsBoolean()) {
            // A hidden page keeps its last read; check again later instead of loading the game.
            this.liveTimer.restart();
            return;
        }
        refresh();
    }

    private void cancelActive() {
        if (this.active != null) {
            this.active.cancel().run();
            this.active = null;
        }
    }

    private void cancelTools() {
        for (SnippetExecutionService.Execution execution : List.copyOf(this.activeTools)) execution.cancel().run();
        this.activeTools.clear();
    }

    private void publish() {
        if (!this.disposed) this.listener.accept(state());
    }

    private static boolean hasProblems(List<FactSection> sections) {
        return sections.stream().flatMap(section -> section.facts().stream())
                .anyMatch(fact -> fact.kind() == Fact.Kind.PROBLEM);
    }

    private static String failureText(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Inspection sessions are used on the EDT");
        }
    }
}
