package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionValuePresentation;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionText;
import com.github.tth05.scnet.Server;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import java.util.function.Consumer;

/** Owns asynchronous MCP code jobs while execution remains inside the Minecraft JVM. */
public final class CodeModeJobService implements AutoCloseable {
    static final int MAX_RETAINED_JOBS = 256;
    static final int MAX_WAIT_MILLISECONDS = 120_000;

    private final Server server;
    private final ExecutorService statusExecutor;
    private final BooleanSupplier available;
    private final Transport transport;
    private final Supplier<Map<String, Object>> runtimeContext;
    private final Clock clock;
    private final AtomicInteger nextScriptId = new AtomicInteger(-1);
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<Integer, String> jobsByScriptId = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public CodeModeJobService(
            Server server,
            BooleanSupplier available,
            Supplier<Map<String, Object>> runtimeContext
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
                            ExecutionEnvironment environment,
                            Consumer<ExecutionResult> failureHandler
                    ) {
                        boolean sent = CompanionApp.runScript(
                                scriptId,
                                source,
                                side == ExecutionSide.SERVER,
                                environment.toWireValue(),
                                failureHandler
                        );
                        if (!sent) {
                            throw new IllegalStateException("Minecraft disconnected while the code job was submitted");
                        }
                    }

                    @Override
                    public void cancel(int scriptId) {
                        if (!CompanionApp.stopScript(scriptId)) {
                            throw new IllegalStateException("Minecraft disconnected before cancellation was sent");
                        }
                    }
                },
                runtimeContext,
                Clock.systemUTC()
        );
        server.getMessageBus().listenAlways(ExecutionResultMessage.class, this, message -> {
            int scriptId = message.scriptId();
            ExecutionResult result = message.result();
            try {
                this.statusExecutor.execute(() -> acceptResult(scriptId, result));
            } catch (RejectedExecutionException ignored) {
            }
        });
    }

    CodeModeJobService(
            BooleanSupplier available,
            Transport transport,
            Clock clock
    ) {
        this(available, transport, Map::of, clock);
    }

    CodeModeJobService(
            BooleanSupplier available,
            Transport transport,
            Supplier<Map<String, Object>> runtimeContext,
            Clock clock
    ) {
        this(
                null,
                null,
                available,
                transport,
                runtimeContext,
                clock
        );
    }

    private CodeModeJobService(
            Server server,
            ExecutorService statusExecutor,
            BooleanSupplier available,
            Transport transport,
            Supplier<Map<String, Object>> runtimeContext,
            Clock clock
    ) {
        this.server = server;
        this.statusExecutor = statusExecutor;
        this.available = Objects.requireNonNull(available, "available");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.runtimeContext = Objects.requireNonNull(runtimeContext, "runtimeContext");
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
                List.copyOf(imports)
        );
        String jobId = UUID.randomUUID().toString();
        Instant submittedAt = this.clock.instant();
        Job job = new Job(
                jobId,
                scriptId,
                side,
                environment,
                generated.source(),
                submittedAt,
                currentRuntimeContext()
        );
        this.jobs.put(jobId, job);
        this.jobsByScriptId.put(scriptId, jobId);

        try {
            this.transport.execute(scriptId, generated.source(), side, environment, result -> acceptResult(scriptId, result));
        } catch (RuntimeException exception) {
            job.finish(
                    JobState.FAILED,
                    null,
                    false,
                    null,
                    "Unable to submit code job: " + exception,
                    this.clock.instant()
            );
            this.jobsByScriptId.remove(scriptId, jobId);
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
        try {
            this.transport.cancel(job.scriptId());
        } catch (RuntimeException exception) {
            job.markCancellationPending(ExecutionText.complete(
                    "Unable to request cancellation; the target may still be running: " + exception), this.clock.instant());
        }
        return true;
    }

    public String source(String jobId) {
        Job job = this.jobs.get(requireJobId(jobId));
        if (job == null) {
            throw new IllegalArgumentException("Unknown or expired job: " + jobId);
        }
        return job.source;
    }

    void acceptResult(int scriptId, ExecutionResult result) {
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

        Objects.requireNonNull(result, "result");
        Instant now = this.clock.instant();
        boolean valuePresent = result.value() != null;
        Object value = valuePresent ? new ExecutionValuePresentation(result.value()).toJsonValue() : null;
        switch (result.status()) {
            case COMPILATION_COMPLETED -> job.markRunning(now);
            case CANCELLATION_PENDING -> job.markCancellationPending(result.error(), now);
            case COMPILATION_FAILED -> job.finish(
                    JobState.FAILED,
                    result.logs(),
                    valuePresent,
                    value,
                    result.error(),
                    now
            );
            case RUN_COMPLETED -> job.finish(
                    JobState.SUCCEEDED,
                    result.logs(),
                    valuePresent,
                    value,
                    result.error(),
                    now
            );
            case RUN_EXCEPTION -> job.finish(
                    job.cancellationRequested() ? JobState.CANCELLED : JobState.FAILED,
                    result.logs(),
                    valuePresent,
                    value,
                    result.error(),
                    now
            );
        }
        if (job.snapshot().state().terminal()) {
            this.jobsByScriptId.remove(scriptId, jobId);
        }
    }

    private void evictCompletedJobs() {
        int excess = this.jobs.size() - MAX_RETAINED_JOBS + 1;
        if (excess <= 0) {
            return;
        }
        this.jobs.values().stream()
                .filter(job -> job.snapshot().state().terminal())
                .sorted(Comparator.comparing((Job job) -> job.snapshot().completedAt())
                        .thenComparing(Comparator.comparingInt(Job::scriptId).reversed()))
                .limit(excess)
                .forEach(job -> this.jobs.remove(job.jobId(), job));
    }

    private Map<String, Object> currentRuntimeContext() {
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

    private static String requireJobId(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        return UUID.fromString(jobId).toString();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.server != null) {
            this.server.getMessageBus().unregister(ExecutionResultMessage.class, this);
        }
        if (this.statusExecutor != null) {
            this.statusExecutor.shutdownNow();
        }
        runtimeDisconnected();
        this.jobsByScriptId.clear();
    }

    public enum ExecutionSide {
        CLIENT,
        SERVER
    }

    public enum ExecutionEnvironment {
        THREAD,
        PRE_TICK,
        POST_TICK;

        private ScriptExecutionEnvironment toWireValue() {
            return ScriptExecutionEnvironment.valueOf(name());
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
            Instant submittedAt,
            Instant updatedAt,
            Instant completedAt,
            boolean cancellationRequested,
            String output,
            boolean outputTruncated,
            int outputTotalCharacters,
            boolean resultPresent,
            Object result,
            String error,
            boolean errorTruncated,
            int errorTotalCharacters,
            Map<String, Object> runtime
    ) {
        public Map<String, Object> responseMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("job_id", this.jobId);
            result.put("state", this.state.name().toLowerCase());
            if (this.output != null && (!this.output.isEmpty() || this.outputTruncated)) {
                result.put("logs", this.output);
                if (this.outputTruncated) {
                    result.put("logs_truncated", true);
                    result.put("logs_total_characters", this.outputTotalCharacters);
                }
            }
            if (this.resultPresent) {
                result.put("result", this.result);
            }
            if (this.error != null) {
                result.put("error", this.error);
                if (this.errorTruncated) {
                    result.put("error_truncated", true);
                    result.put("error_total_characters", this.errorTotalCharacters);
                }
            }
            return result;
        }

    }

    interface Transport {
        void execute(int scriptId, String source, ExecutionSide side, ExecutionEnvironment environment,
                     Consumer<ExecutionResult> failureHandler);

        void cancel(int scriptId);
    }

    private static final class Job {
        private final String jobId;
        private final int scriptId;
        private final ExecutionSide side;
        private final ExecutionEnvironment environment;
        private final String source;
        private final Instant submittedAt;
        private final Map<String, Object> runtime;
        private JobState state = JobState.COMPILING;
        private Instant updatedAt;
        private Instant completedAt;
        private boolean cancellationRequested;
        private String output;
        private boolean outputTruncated;
        private int outputTotalCharacters;
        private boolean resultPresent;
        private Object result;
        private String error;
        private boolean errorTruncated;
        private int errorTotalCharacters;

        private Job(
                String jobId,
                int scriptId,
                ExecutionSide side,
                ExecutionEnvironment environment,
                String source,
                Instant submittedAt,
                Map<String, Object> runtime
        ) {
            this.jobId = jobId;
            this.scriptId = scriptId;
            this.side = side;
            this.environment = environment;
            this.source = source;
            this.submittedAt = submittedAt;
            this.runtime = Map.copyOf(runtime);
            this.updatedAt = submittedAt;
        }

        private synchronized void markRunning(Instant now) {
            if (this.state.terminal() || this.state == JobState.CANCELLING) {
                return;
            }
            this.state = JobState.RUNNING;
            this.updatedAt = now;
        }

        private synchronized boolean requestCancellation(Instant now) {
            if (this.state.terminal()) {
                return false;
            }
            this.cancellationRequested = true;
            this.state = JobState.CANCELLING;
            this.updatedAt = now;
            return true;
        }

        private synchronized void markCancellationPending(ExecutionText message, Instant now) {
            if (this.state.terminal()) return;
            this.cancellationRequested = true;
            this.state = JobState.CANCELLING;
            this.error = message.text();
            this.errorTruncated = message.truncated();
            this.errorTotalCharacters = message.totalCharacters();
            this.updatedAt = now;
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
            this.outputTruncated = false;
            this.outputTotalCharacters = output == null ? 0 : output.length();
            this.resultPresent = resultPresent;
            this.result = result;
            this.error = error;
            this.errorTruncated = false;
            this.errorTotalCharacters = error == null ? 0 : error.length();
            this.updatedAt = now;
            this.completedAt = now;
            notifyAll();
        }

        private synchronized void finish(
                JobState state,
                ExecutionText logs,
                boolean resultPresent,
                Object result,
                ExecutionText error,
                Instant now
        ) {
            String errorText = error.text().isEmpty() && !error.truncated() ? null : error.text();
            finish(state, logs.text(), resultPresent, result, errorText, now);
            this.outputTruncated = logs.truncated();
            this.outputTotalCharacters = logs.totalCharacters();
            this.errorTruncated = error.truncated();
            this.errorTotalCharacters = error.totalCharacters();
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
            return new JobSnapshot(
                    this.jobId,
                    this.scriptId,
                    this.state,
                    this.side,
                    this.environment,
                    this.submittedAt,
                    this.updatedAt,
                    this.completedAt,
                    this.cancellationRequested,
                    this.output,
                    this.outputTruncated,
                    this.outputTotalCharacters,
                    this.resultPresent,
                    this.result,
                    this.error,
                    this.errorTruncated,
                    this.errorTotalCharacters,
                    this.runtime
            );
        }
    }

}
