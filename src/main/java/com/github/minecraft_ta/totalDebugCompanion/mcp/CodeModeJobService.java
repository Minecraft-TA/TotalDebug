package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.jdt.BaseScript;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.RunScriptMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.ScriptStatusMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.StopScriptMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.github.tth05.scnet.Server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Owns asynchronous MCP code jobs while execution remains inside the Minecraft JVM. */
public final class CodeModeJobService implements AutoCloseable {
    static final int MAX_RETAINED_JOBS = 256;
    static final int MAX_OUTPUT_CHARACTERS = 250_000;
    static final int MAX_WAIT_MILLISECONDS = 120_000;
    private static final String TRUNCATED_SUFFIX = "\n[Companion truncated the code-mode output]";
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private final Server server;
    private final ExecutorService statusExecutor;
    private final BooleanSupplier available;
    private final Transport transport;
    private final UnaryOperator<String> baseScriptMerger;
    private final Supplier<Map<String, Object>> runtimeContext;
    private final CodeModeArtifactStore artifacts;
    private final Clock clock;
    private final AtomicInteger nextScriptId = new AtomicInteger(-1);
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<Integer, String> jobsByScriptId = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public CodeModeJobService(
            Server server,
            BooleanSupplier available,
            Supplier<Map<String, Object>> runtimeContext,
            Path artifactDirectory
    ) {
        this(
                Objects.requireNonNull(server, "server"),
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "Companion code-mode status");
                    thread.setDaemon(true);
                    return thread;
                }),
                available,
                new Transport() {
                    @Override
                    public void execute(
                            int scriptId,
                            String source,
                            ExecutionSide side,
                            ExecutionEnvironment environment
                    ) {
                        boolean sent = CompanionApp.send(new RunScriptMessage(
                                scriptId,
                                source,
                                side == ExecutionSide.SERVER,
                                environment.toWireValue()
                        ));
                        if (!sent) {
                            throw new IllegalStateException("Minecraft disconnected while the code job was submitted");
                        }
                    }

                    @Override
                    public void cancel(int scriptId) {
                        if (!CompanionApp.send(new StopScriptMessage(scriptId))) {
                            throw new IllegalStateException("Minecraft disconnected before cancellation was sent");
                        }
                    }
                },
                BaseScript::mergeWithNormalScript,
                runtimeContext,
                new CodeModeArtifactStore(artifactDirectory),
                Clock.systemUTC()
        );
        server.getMessageBus().listenAlways(ScriptStatusMessage.class, this, message -> {
            int scriptId = message.getScriptId();
            ScriptStatusMessage.Type type = message.getType();
            try {
                this.statusExecutor.execute(() -> acceptStatus(
                        scriptId,
                        type,
                        message.getOutput(),
                        message.getResultJson(),
                        message.getError()
                ));
            } catch (RejectedExecutionException ignored) {
            }
        });
    }

    CodeModeJobService(
            BooleanSupplier available,
            Transport transport,
            UnaryOperator<String> baseScriptMerger,
            Path artifactDirectory,
            Clock clock
    ) {
        this(available, transport, baseScriptMerger, Map::of, artifactDirectory, clock);
    }

    CodeModeJobService(
            BooleanSupplier available,
            Transport transport,
            UnaryOperator<String> baseScriptMerger,
            Supplier<Map<String, Object>> runtimeContext,
            Path artifactDirectory,
            Clock clock
    ) {
        this(
                null,
                null,
                available,
                transport,
                baseScriptMerger,
                runtimeContext,
                new CodeModeArtifactStore(artifactDirectory),
                clock
        );
    }

    private CodeModeJobService(
            Server server,
            ExecutorService statusExecutor,
            BooleanSupplier available,
            Transport transport,
            UnaryOperator<String> baseScriptMerger,
            Supplier<Map<String, Object>> runtimeContext,
            CodeModeArtifactStore artifacts,
            Clock clock
    ) {
        this.server = server;
        this.statusExecutor = statusExecutor;
        this.available = Objects.requireNonNull(available, "available");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.baseScriptMerger = Objects.requireNonNull(baseScriptMerger, "baseScriptMerger");
        this.runtimeContext = Objects.requireNonNull(runtimeContext, "runtimeContext");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean isAvailable() {
        return !this.closed && this.available.getAsBoolean();
    }

    public JobSnapshot submit(
            String code,
            List<String> imports,
            ExecutionSide side,
            ExecutionEnvironment environment
    ) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(environment, "environment");
        if (!isAvailable()) {
            throw new IllegalStateException("Minecraft script execution is not available in this Companion session");
        }
        evictCompletedJobs();

        int scriptId = nextScriptId();
        CodeModeSourceBuilder.GeneratedSource generated = CodeModeSourceBuilder.build(
                scriptId,
                code,
                List.copyOf(imports),
                this.baseScriptMerger
        );
        String jobId = UUID.randomUUID().toString();
        Instant submittedAt = this.clock.instant();
        Job job = new Job(
                jobId,
                scriptId,
                side,
                environment,
                generated.className(),
                sha256(generated.source()),
                generated.sourceBytes(),
                submittedAt,
                currentRuntimeContext()
        );
        this.jobs.put(jobId, job);
        this.jobsByScriptId.put(scriptId, jobId);

        try {
            CodeModeArtifactStore.ArtifactPaths paths = this.artifacts.create(
                    jobId,
                    generated.className(),
                    generated.source(),
                    job.snapshot().asMap()
            );
            job.setArtifacts(paths);
            persist(job);
            this.transport.execute(scriptId, generated.source(), side, environment);
        } catch (IOException | RuntimeException exception) {
            job.finish(
                    JobState.FAILED,
                    null,
                    false,
                    null,
                    "Unable to submit code job: " + exception,
                    this.clock.instant()
            );
            this.jobsByScriptId.remove(scriptId, jobId);
            persist(job);
        }
        return job.snapshot();
    }

    public Optional<JobSnapshot> get(String jobId) {
        Job job = this.jobs.get(requireJobId(jobId));
        return job == null ? Optional.empty() : Optional.of(job.snapshot());
    }

    public JobSnapshot waitFor(String jobId, int waitMilliseconds) {
        if (waitMilliseconds < 0 || waitMilliseconds > MAX_WAIT_MILLISECONDS) {
            throw new IllegalArgumentException(
                    "waitMilliseconds must be between 0 and " + MAX_WAIT_MILLISECONDS
            );
        }
        Job job = this.jobs.get(requireJobId(jobId));
        if (job == null) {
            throw new IllegalArgumentException("Unknown job");
        }
        try {
            return job.awaitTerminal(waitMilliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the code job", exception);
        }
    }

    public List<JobSnapshot> list(int limit) {
        if (limit < 1 || limit > MAX_RETAINED_JOBS) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_RETAINED_JOBS);
        }
        return this.jobs.values().stream()
                .map(Job::snapshot)
                .sorted(Comparator.comparing(JobSnapshot::submittedAt).reversed())
                .limit(limit)
                .toList();
    }

    public boolean cancel(String jobId) {
        Job job = this.jobs.get(requireJobId(jobId));
        if (job == null || !job.requestCancellation(this.clock.instant())) {
            return false;
        }
        persist(job);
        try {
            this.transport.cancel(job.scriptId());
        } catch (RuntimeException exception) {
            job.finish(
                    JobState.FAILED,
                    null,
                    false,
                    null,
                    "Unable to request cancellation: " + exception,
                    this.clock.instant()
            );
            this.jobsByScriptId.remove(job.scriptId(), job.jobId());
            persist(job);
        }
        return true;
    }

    public String readArtifact(String jobId, String artifact) throws IOException {
        requireJobId(jobId);
        return this.artifacts.read(jobId, artifact);
    }

    void acceptStatus(
            int scriptId,
            ScriptStatusMessage.Type type,
            String output,
            String resultJson,
            String error
    ) {
        if (this.closed) {
            return;
        }
        String jobId = this.jobsByScriptId.get(scriptId);
        if (jobId == null) {
            return;
        }
        Job job = this.jobs.get(jobId);
        if (job == null) {
            return;
        }

        Instant now = this.clock.instant();
        ParsedResult parsedResult;
        try {
            parsedResult = parseResult(resultJson);
        } catch (JsonParseException exception) {
            job.finish(
                    JobState.FAILED,
                    bounded(output),
                    false,
                    null,
                    "Minecraft returned an invalid structured result: " + exception.getMessage(),
                    now
            );
            this.jobsByScriptId.remove(scriptId, jobId);
            persist(job);
            return;
        }
        switch (Objects.requireNonNull(type, "type")) {
            case COMPILATION_COMPLETED -> job.markRunning(now);
            case COMPILATION_FAILED -> job.finish(JobState.FAILED, null, false, null, error, now);
            case RUN_COMPLETED -> job.finish(
                    JobState.SUCCEEDED,
                    bounded(output),
                    parsedResult.present(),
                    parsedResult.value(),
                    null,
                    now
            );
            case RUN_EXCEPTION -> job.finish(
                    job.cancellationRequested() ? JobState.CANCELLED : JobState.FAILED,
                    bounded(output),
                    parsedResult.present(),
                    parsedResult.value(),
                    bounded(error),
                    now
            );
        }
        if (job.snapshot().state().terminal()) {
            this.jobsByScriptId.remove(scriptId, jobId);
        }
        persist(job);
    }

    private void evictCompletedJobs() {
        int excess = this.jobs.size() - MAX_RETAINED_JOBS + 1;
        if (excess <= 0) {
            return;
        }
        this.jobs.values().stream()
                .filter(job -> job.snapshot().state().terminal())
                .sorted(Comparator.comparing(job -> job.snapshot().completedAt()))
                .limit(excess)
                .forEach(job -> this.jobs.remove(job.jobId(), job));
    }

    public Map<String, Object> currentRuntimeContext() {
        return Map.copyOf(Objects.requireNonNull(this.runtimeContext.get(), "runtimeContext returned null"));
    }

    public void runtimeDisconnected() {
        if (this.statusExecutor != null && !this.closed) {
            try {
                this.statusExecutor.execute(this::markRuntimeDisconnected);
                return;
            } catch (RejectedExecutionException ignored) {
            }
        }
        markRuntimeDisconnected();
    }

    private void markRuntimeDisconnected() {
        Instant now = this.clock.instant();
        for (Job job : new ArrayList<>(this.jobs.values())) {
            if (job.disconnect(now)) {
                this.jobsByScriptId.remove(job.scriptId(), job.jobId());
                persist(job);
            }
        }
    }

    private int nextScriptId() {
        int scriptId = this.nextScriptId.getAndUpdate(current -> current == Integer.MIN_VALUE
                ? Integer.MIN_VALUE
                : current - 1);
        if (scriptId == Integer.MIN_VALUE) {
            throw new IllegalStateException("Code-mode script id space is exhausted");
        }
        return scriptId;
    }

    private void persist(Job job) {
        CodeModeArtifactStore.ArtifactPaths paths = job.artifacts();
        if (paths == null) {
            return;
        }
        try {
            this.artifacts.update(paths, job.snapshot().asMap());
        } catch (IOException exception) {
            System.err.println("Unable to update code-mode artifact for " + job.jobId() + ": " + exception);
        }
    }

    private static String requireJobId(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        return UUID.fromString(jobId).toString();
    }

    private static String bounded(String value) {
        String message = Objects.requireNonNullElse(value, "");
        if (message.length() <= MAX_OUTPUT_CHARACTERS) {
            return message;
        }
        return message.substring(0, MAX_OUTPUT_CHARACTERS - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
    }

    private static String sha256(String source) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.server != null) {
            this.server.getMessageBus().unregister(ScriptStatusMessage.class, this);
        }
        if (this.statusExecutor != null) {
            this.statusExecutor.shutdownNow();
        }
        runtimeDisconnected();
        this.jobsByScriptId.clear();
    }

    private static ParsedResult parseResult(String resultJson) throws JsonParseException {
        if (resultJson == null) {
            return new ParsedResult(false, null);
        }
        return new ParsedResult(true, GSON.fromJson(resultJson, Object.class));
    }

    public enum ExecutionSide {
        CLIENT,
        SERVER
    }

    public enum ExecutionEnvironment {
        THREAD,
        PRE_TICK,
        POST_TICK;

        private RunScriptMessage.ExecutionEnvironment toWireValue() {
            return RunScriptMessage.ExecutionEnvironment.valueOf(name());
        }
    }

    public enum JobState {
        COMPILING,
        RUNNING,
        CANCELLING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        DISCONNECTED;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == DISCONNECTED;
        }
    }

    public record JobSnapshot(
            String jobId,
            int scriptId,
            JobState state,
            ExecutionSide side,
            ExecutionEnvironment environment,
            String className,
            String sourceSha256,
            int sourceBytes,
            Instant submittedAt,
            Instant updatedAt,
            Instant completedAt,
            boolean cancellationRequested,
            String output,
            boolean resultPresent,
            Object result,
            String error,
            Map<String, Object> artifacts,
            Map<String, Object> runtime
    ) {
        public Map<String, Object> responseMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("job_id", this.jobId);
            result.put("state", this.state.name().toLowerCase());
            if (this.output != null && !this.output.isEmpty()) {
                result.put("logs", this.output);
            }
            if (this.resultPresent) {
                result.put("result", this.result);
            }
            if (this.error != null) {
                result.put("error", this.error);
            }
            return result;
        }

        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("job_id", this.jobId);
            result.put("script_id", this.scriptId);
            result.put("state", this.state.name().toLowerCase());
            result.put("side", this.side.name().toLowerCase());
            result.put("environment", this.environment.name().toLowerCase());
            result.put("class_name", this.className);
            result.put("source_sha256", this.sourceSha256);
            result.put("source_bytes", this.sourceBytes);
            result.put("submitted_at", this.submittedAt.toString());
            result.put("updated_at", this.updatedAt.toString());
            if (this.completedAt != null) {
                result.put("completed_at", this.completedAt.toString());
            }
            result.put("cancellation_requested", this.cancellationRequested);
            if (this.output != null) {
                result.put("output", this.output);
            }
            if (this.resultPresent) {
                result.put("result", this.result);
            }
            if (this.error != null) {
                result.put("error", this.error);
            }
            result.put("artifacts", this.artifacts);
            result.put("runtime", this.runtime);
            return result;
        }
    }

    interface Transport {
        void execute(int scriptId, String source, ExecutionSide side, ExecutionEnvironment environment);

        void cancel(int scriptId);
    }

    private static final class Job {
        private final String jobId;
        private final int scriptId;
        private final ExecutionSide side;
        private final ExecutionEnvironment environment;
        private final String className;
        private final String sourceSha256;
        private final int sourceBytes;
        private final Instant submittedAt;
        private final Map<String, Object> runtime;
        private JobState state = JobState.COMPILING;
        private Instant updatedAt;
        private Instant completedAt;
        private boolean cancellationRequested;
        private String output;
        private boolean resultPresent;
        private Object result;
        private String error;
        private CodeModeArtifactStore.ArtifactPaths artifacts;

        private Job(
                String jobId,
                int scriptId,
                ExecutionSide side,
                ExecutionEnvironment environment,
                String className,
                String sourceSha256,
                int sourceBytes,
                Instant submittedAt,
                Map<String, Object> runtime
        ) {
            this.jobId = jobId;
            this.scriptId = scriptId;
            this.side = side;
            this.environment = environment;
            this.className = className;
            this.sourceSha256 = sourceSha256;
            this.sourceBytes = sourceBytes;
            this.submittedAt = submittedAt;
            this.runtime = Map.copyOf(runtime);
            this.updatedAt = submittedAt;
        }

        private synchronized void setArtifacts(CodeModeArtifactStore.ArtifactPaths artifacts) {
            this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        }

        private synchronized CodeModeArtifactStore.ArtifactPaths artifacts() {
            return this.artifacts;
        }

        private synchronized void markRunning(Instant now) {
            if (this.state.terminal() || this.state == JobState.CANCELLING) {
                return;
            }
            this.state = JobState.RUNNING;
            this.updatedAt = now;
        }

        private synchronized boolean requestCancellation(Instant now) {
            if (this.state.terminal() || this.state == JobState.CANCELLING) {
                return false;
            }
            this.cancellationRequested = true;
            this.state = JobState.CANCELLING;
            this.updatedAt = now;
            return true;
        }

        private synchronized void finish(
                JobState state,
                String output,
                boolean resultPresent,
                Object result,
                String error,
                Instant now
        ) {
            if (this.state.terminal()) {
                return;
            }
            if (!state.terminal()) {
                throw new IllegalArgumentException("finish requires a terminal state");
            }
            this.state = state;
            this.output = output;
            this.resultPresent = resultPresent;
            this.result = result;
            this.error = error;
            this.updatedAt = now;
            this.completedAt = now;
            notifyAll();
        }

        private synchronized JobSnapshot awaitTerminal(int waitMilliseconds) throws InterruptedException {
            if (!this.state.terminal() && waitMilliseconds > 0) {
                long deadline = System.nanoTime() + waitMilliseconds * 1_000_000L;
                long remaining;
                while (!this.state.terminal() && (remaining = deadline - System.nanoTime()) > 0) {
                    wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                }
            }
            return snapshot();
        }

        private synchronized boolean disconnect(Instant now) {
            if (this.state.terminal()) {
                return false;
            }
            finish(
                    JobState.DISCONNECTED,
                    null,
                    false,
                    null,
                    "Minecraft disconnected before the job completed",
                    now
            );
            return true;
        }

        private synchronized boolean cancellationRequested() {
            return this.cancellationRequested;
        }

        private synchronized int scriptId() {
            return this.scriptId;
        }

        private synchronized String jobId() {
            return this.jobId;
        }

        private synchronized JobSnapshot snapshot() {
            Map<String, Object> artifactMap = this.artifacts == null ? Map.of() : this.artifacts.asMap();
            return new JobSnapshot(
                    this.jobId,
                    this.scriptId,
                    this.state,
                    this.side,
                    this.environment,
                    this.className,
                    this.sourceSha256,
                    this.sourceBytes,
                    this.submittedAt,
                    this.updatedAt,
                    this.completedAt,
                    this.cancellationRequested,
                    this.output,
                    this.resultPresent,
                    this.result,
                    this.error,
                    artifactMap,
                    this.runtime
            );
        }
    }

    private record ParsedResult(boolean present, Object value) {
    }
}
