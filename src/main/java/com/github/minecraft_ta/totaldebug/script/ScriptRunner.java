package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.evaluation.InMemoryJavaCompiler;
import com.github.minecraft_ta.totaldebug.evaluation.InMemoryCompilationException;
import com.github.minecraft_ta.totaldebug.evaluation.ScriptClassLoader;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.tick.TickPhase;
import net.minecraft.world.level.block.Block;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles, defines, runs, and cooperatively cancels live Java scripts. */
public final class ScriptRunner implements AutoCloseable {
    static final Duration DEFAULT_STOP_GRACE = Duration.ofSeconds(1);
    private static final Pattern SCRIPT_CLASS_PATTERN = Pattern.compile(
            "\\bpublic\\s+(?:final\\s+)?class\\s+"
                    + "([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)"
                    + "\\s+extends\\s+(?:com\\.github\\.minecraft_ta\\.totaldebug\\.script\\.)?ScriptProgram\\b"
    );

    private final ScriptCompilerClasspath classpath;
    private final ClassLoader parentClassLoader;
    private final ScriptTickScheduler tickScheduler;
    private final ExecutionResultSink resultSink;
    private final InMemoryJavaCompiler compiler;
    private final Duration stopGrace;
    private final ExecutorService compilerExecutor;
    private final ScheduledExecutorService stopExecutor;
    private final Map<Integer, ScriptRun> runs = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile boolean moduleAccessLogged;

    public ScriptRunner(
            ScriptCompilerClasspath classpath,
            ClassLoader parentClassLoader,
            ScriptTickScheduler tickScheduler,
            ExecutionResultSink resultSink
    ) {
        this(
                classpath,
                parentClassLoader,
                tickScheduler,
                resultSink,
                new InMemoryJavaCompiler(),
                DEFAULT_STOP_GRACE,
                Executors.newSingleThreadExecutor(runnable -> daemonThread(
                        runnable,
                        "TotalDebug script compiler"
                )),
                Executors.newSingleThreadScheduledExecutor(runnable -> daemonThread(
                        runnable,
                        "TotalDebug script stop monitor"
                ))
        );
    }

    ScriptRunner(
            ScriptCompilerClasspath classpath,
            ClassLoader parentClassLoader,
            ScriptTickScheduler tickScheduler,
            ExecutionResultSink resultSink,
            InMemoryJavaCompiler compiler,
            Duration stopGrace,
            ExecutorService compilerExecutor,
            ScheduledExecutorService stopExecutor
    ) {
        this.classpath = Objects.requireNonNull(classpath, "classpath");
        this.parentClassLoader = Objects.requireNonNull(parentClassLoader, "parentClassLoader");
        this.tickScheduler = Objects.requireNonNull(tickScheduler, "tickScheduler");
        this.resultSink = Objects.requireNonNull(resultSink, "resultSink");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.stopGrace = Objects.requireNonNull(stopGrace, "stopGrace");
        if (stopGrace.isNegative() || stopGrace.isZero()) {
            throw new IllegalArgumentException("stopGrace must be positive");
        }
        this.compilerExecutor = Objects.requireNonNull(compilerExecutor, "compilerExecutor");
        this.stopExecutor = Objects.requireNonNull(stopExecutor, "stopExecutor");
    }

    public void runScript(
            int scriptId,
            String sourceCode,
            ScriptExecutionEnvironment environment
    ) {
        Objects.requireNonNull(sourceCode, "sourceCode");
        Objects.requireNonNull(environment, "environment");
        if (this.closed) {
            sendResult(scriptId, ExecutionStatus.COMPILATION_FAILED, "The script runner is closed");
            return;
        }

        ScriptRun run = new ScriptRun(scriptId, sourceCode, environment);
        if (this.runs.putIfAbsent(scriptId, run) != null) {
            sendResult(
                    scriptId,
                    ExecutionStatus.COMPILATION_FAILED,
                    "A script with this id is already running"
            );
            return;
        }

        try {
            Future<?> future = this.compilerExecutor.submit(() -> compileAndSchedule(run));
            run.installCompilationFuture(future);
        } catch (RuntimeException exception) {
            run.finish(ExecutionStatus.COMPILATION_FAILED, "Unable to start script compilation: " + exception);
        }
    }

    public void stopScript(int scriptId) {
        ScriptRun run = this.runs.get(scriptId);
        if (run != null) {
            run.stop();
        }
    }

    public void stopAll() {
        for (ScriptRun run : new ArrayList<>(this.runs.values())) {
            run.stop();
        }
    }

    boolean isExecutionStarted(int scriptId) {
        ScriptRun run = this.runs.get(scriptId);
        return run != null && run.isExecutionStarted();
    }

    private void compileAndSchedule(ScriptRun run) {
        if (run.isTerminalOrCancelled()) {
            return;
        }

        String className;
        try {
            className = extractScriptClassName(run.sourceCode);
        } catch (IllegalArgumentException exception) {
            run.finish(ExecutionStatus.COMPILATION_FAILED, exception.getMessage());
            return;
        }

        CompiledScript compiledScript;
        try {
            Map<String, byte[]> bytecode = this.classpath.compile(this.compiler, run.sourceCode, className);
            if (run.isTerminalOrCancelled()) {
                return;
            }
            ScriptClassLoader classLoader = new ScriptClassLoader(this.parentClassLoader, bytecode);
            logModuleAccessOnce(classLoader);
            compiledScript = CompiledScript.load(classLoader, className);
        } catch (InMemoryCompilationException exception) {
            run.finish(ExecutionStatus.COMPILATION_FAILED, exception.getMessage());
            return;
        } catch (Throwable throwable) {
            run.finish(ExecutionResult.failure(
                    ExecutionStatus.COMPILATION_FAILED,
                    prepend("Unable to define the compiled script: ", shortenedStackTrace(throwable, className))
            ));
            return;
        }

        Runnable execution = () -> execute(run, compiledScript);
        try {
            switch (run.environment) {
                case THREAD -> {
                    Thread thread = daemonThread(execution, "TotalDebug script " + run.scriptId);
                    run.scheduleAndReportCompilation(thread::start);
                }
                case PRE_TICK -> run.scheduleAndReportCompilation(
                        () -> this.tickScheduler.submit(TickPhase.PRE, execution)
                );
                case POST_TICK -> run.scheduleAndReportCompilation(
                        () -> this.tickScheduler.submit(TickPhase.POST, execution)
                );
            }
        } catch (Throwable throwable) {
            run.finish(ExecutionResult.failed(
                    ExecutionText.empty(),
                    null,
                    prepend("Unable to schedule the script: ", shortenedStackTrace(throwable, className))
            ));
        }
    }

    private void execute(ScriptRun run, CompiledScript compiledScript) {
        if (!run.beginExecution(Thread.currentThread())) {
            return;
        }

        ScriptExecutionOutcome outcome;
        try {
            outcome = compiledScript.execute();
        } catch (Throwable throwable) {
            outcome = new ScriptExecutionOutcome(
                    ExecutionText.empty(), null, unwrapInvocationException(throwable)
            );
        } finally {
            run.clearExecutionThread(Thread.currentThread());
        }

        if (run.isCancellationRequested()) {
            run.finish(ExecutionResult.failed(
                    outcome.output(), outcome.value(), "Script run cancelled"
            ));
        } else if (outcome.failure() != null) {
            run.finish(ExecutionResult.failed(
                    outcome.output(),
                    outcome.value(),
                    shortenedStackTrace(outcome.failure(), compiledScript.className)
            ));
        } else {
            run.finish(ExecutionResult.completed(outcome.output(), outcome.value()));
        }
    }

    private void logModuleAccessOnce(ScriptClassLoader classLoader) {
        if (this.moduleAccessLogged) {
            return;
        }
        synchronized (this) {
            if (this.moduleAccessLogged) {
                return;
            }
            Module minecraftModule = Block.class.getModule();
            Module scriptModule = classLoader.getUnnamedModule();
            String blockPackage = Block.class.getPackageName();
            TotalDebug.LOGGER.info(
                    "Live script module access for {} from unnamed module: exported={}, open={}",
                    blockPackage,
                    minecraftModule.isExported(blockPackage, scriptModule),
                    minecraftModule.isOpen(blockPackage, scriptModule)
            );
            this.moduleAccessLogged = true;
        }
    }

    private static String extractScriptClassName(String sourceCode) {
        Matcher matcher = SCRIPT_CLASS_PATTERN.matcher(sourceCode);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Script source must contain a public class that directly extends ScriptProgram"
            );
        }
        return matcher.group(1);
    }

    private static Throwable unwrapInvocationException(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current;
    }

    private static ExecutionText shortenedStackTrace(Throwable throwable, String className) {
        return renderStackTrace(throwable, className, ExecutionResultCodec.MAX_WIRE_BYTES);
    }

    static ExecutionText renderStackTrace(Throwable throwable, String className, int maxCharacters) {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("maxCharacters must be positive");
        }
        ExecutionTextBuffer output = new ExecutionTextBuffer(maxCharacters);
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        boolean causedBy = false;
        while (current != null && visited.add(current)) {
            if (causedBy) {
                output.append("Caused by: ");
            }
            output.append(current.getClass().getName());
            String message = current.getMessage();
            if (message != null && !message.isEmpty()) {
                output.append(": ");
                output.append(message);
            }
            output.append(System.lineSeparator());
            if (output.isFull()) {
                return truncatedStackTrace(output);
            }
            for (StackTraceElement frame : current.getStackTrace()) {
                output.append("\tat ");
                output.append(frame);
                output.append(System.lineSeparator());
                if (frame.getClassName().equals(className)) {
                    return output.snapshot();
                }
                if (output.isFull()) {
                    return truncatedStackTrace(output);
                }
            }
            current = current.getCause();
            causedBy = true;
        }
        return output.snapshot();
    }

    private static ExecutionText truncatedStackTrace(ExecutionTextBuffer output) {
        output.append("[remaining stack trace omitted]");
        return output.snapshot();
    }

    private static ExecutionText prepend(String prefix, ExecutionText value) {
        ExecutionTextBuffer result = new ExecutionTextBuffer(ExecutionResultCodec.MAX_WIRE_BYTES);
        result.append(prefix);
        result.append(value);
        return result.snapshot();
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private void sendResult(int scriptId, ExecutionStatus type, String message) {
        sendResult(scriptId, ExecutionResult.fromStatus(type, message));
    }

    private void sendResult(int scriptId, ExecutionResult result) {
        try {
            this.resultSink.send(scriptId, result);
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.warn(
                    "Unable to send script status {} for script {}",
                    result.status(),
                    scriptId,
                    exception
            );
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        stopAll();
        this.compilerExecutor.shutdownNow();
        this.stopExecutor.shutdown();
    }

    private final class ScriptRun {
        private final Object lock = new Object();
        private final int scriptId;
        private final String sourceCode;
        private final ScriptExecutionEnvironment environment;
        private Future<?> compilationFuture;
        private Thread executionThread;
        private boolean executionStarted;
        private boolean cancellationRequested;
        private boolean terminal;

        private ScriptRun(int scriptId, String sourceCode, ScriptExecutionEnvironment environment) {
            this.scriptId = scriptId;
            this.sourceCode = sourceCode;
            this.environment = environment;
        }

        private void installCompilationFuture(Future<?> future) {
            synchronized (this.lock) {
                this.compilationFuture = future;
                if (this.terminal || this.cancellationRequested) {
                    future.cancel(true);
                }
            }
        }

        private boolean scheduleAndReportCompilation(Runnable schedulingAction) {
            synchronized (this.lock) {
                if (this.terminal || this.cancellationRequested) {
                    return false;
                }
                schedulingAction.run();
                sendResult(this.scriptId, ExecutionStatus.COMPILATION_COMPLETED, "");
                return true;
            }
        }

        private boolean beginExecution(Thread thread) {
            synchronized (this.lock) {
                if (this.terminal || this.cancellationRequested) {
                    return false;
                }
                this.executionStarted = true;
                this.executionThread = thread;
                return true;
            }
        }

        private void clearExecutionThread(Thread thread) {
            synchronized (this.lock) {
                if (this.executionThread == thread) {
                    this.executionThread = null;
                }
            }
        }

        private boolean isTerminalOrCancelled() {
            synchronized (this.lock) {
                return this.terminal || this.cancellationRequested;
            }
        }

        private boolean isCancellationRequested() {
            synchronized (this.lock) {
                return this.cancellationRequested;
            }
        }

        private boolean isExecutionStarted() {
            synchronized (this.lock) {
                return this.executionStarted;
            }
        }

        private void stop() {
            Thread threadToInterrupt = null;
            boolean cancelBeforeStart = false;
            boolean cannotStopTickThread = false;
            synchronized (this.lock) {
                if (this.terminal) {
                    return;
                }
                this.cancellationRequested = true;
                if (this.compilationFuture != null) {
                    this.compilationFuture.cancel(true);
                }
                if (!this.executionStarted) {
                    cancelBeforeStart = true;
                } else if (this.environment == ScriptExecutionEnvironment.THREAD) {
                    threadToInterrupt = this.executionThread;
                } else {
                    cannotStopTickThread = true;
                }
            }

            if (cancelBeforeStart) {
                finish(ExecutionStatus.RUN_EXCEPTION, "Script run cancelled before execution");
                return;
            }
            if (cannotStopTickThread) {
                finish(
                        ExecutionStatus.RUN_EXCEPTION,
                        "The script is already running on the game thread and cannot be stopped safely"
                );
                return;
            }
            if (threadToInterrupt == null) {
                finish(ExecutionStatus.RUN_EXCEPTION, "Script run cancelled");
                return;
            }

            Thread executingThread = threadToInterrupt;
            executingThread.interrupt();
            stopExecutor.schedule(() -> {
                if (executingThread.isAlive()) {
                    finish(
                            ExecutionStatus.RUN_EXCEPTION,
                            "Stop timed out; the script is still running and can only end cooperatively or when Minecraft exits"
                    );
                }
            }, stopGrace.toNanos(), TimeUnit.NANOSECONDS);
        }

        private void finish(ExecutionStatus type, String message) {
            finish(ExecutionResult.fromStatus(type, message));
        }

        private void finish(ExecutionResult status) {
            synchronized (this.lock) {
                if (this.terminal) {
                    return;
                }
                this.terminal = true;
                sendResult(this.scriptId, status);
            }
            runs.remove(this.scriptId, this);
        }
    }

    private record CompiledScript(Class<? extends ScriptProgram> scriptClass, String className) {
        private static CompiledScript load(ClassLoader classLoader, String className) throws ReflectiveOperationException {
            Class<?> scriptClass = classLoader.loadClass(className);
            if (scriptClass.getSuperclass() != ScriptProgram.class) {
                throw new IllegalArgumentException(className + " does not directly extend ScriptProgram");
            }
            return new CompiledScript(scriptClass.asSubclass(ScriptProgram.class), className);
        }

        private ScriptExecutionOutcome execute() throws Throwable {
            ScriptProgram instance = this.scriptClass.getDeclaredConstructor().newInstance();
            Throwable failure = null;
            Object result = null;
            try {
                result = instance.run();
            } catch (Throwable throwable) {
                failure = throwable;
            }
            ExecutionText output = instance.output();
            ExecutionValue value = null;
            if (failure == null && !instance.isNoResult(result)) {
                try {
                    value = ExecutionValueCapture.capture(result);
                } catch (Throwable captureFailure) {
                    if (failure == null) {
                        failure = new IllegalArgumentException(
                                "Unable to capture the structured script result",
                                captureFailure
                        );
                    } else {
                        failure.addSuppressed(captureFailure);
                    }
                    value = null;
                }
            }
            return new ScriptExecutionOutcome(output, value, failure);
        }
    }

    private record ScriptExecutionOutcome(
            ExecutionText output,
            ExecutionValue value,
            Throwable failure
    ) {
    }
}
