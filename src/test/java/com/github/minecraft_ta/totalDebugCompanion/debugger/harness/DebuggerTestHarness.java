package com.github.minecraft_ta.totalDebugCompanion.debugger.harness;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugTargetDescriptor;
import com.github.minecraft_ta.totalDebugCompanion.debugger.LocalJvmDebugTargetResolver;
import com.github.minecraft_ta.totalDebugCompanion.debugger.MicrosoftJavaDebugEngine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a compiled Java fixture in a child JVM with the real JDWP agent and attaches the Companion
 * debugger to it. Tests and the manual debugger harness share this class so launch, event waiting,
 * source registration, inspection, timeouts and cleanup have one implementation.
 */
public final class DebuggerTestHarness implements AutoCloseable {
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern JDWP_ADDRESS = Pattern.compile(".*address: (\\d+)");

    private final Path sourcePath;
    private final String sourceText;
    private final URI sourceUri;
    private final DebugEngine.Source source;
    private final Process process;
    private final BufferedReader output;
    private final LinkedBlockingQueue<DebugEngine.StoppedEvent> stops = new LinkedBlockingQueue<>();
    private MicrosoftJavaDebugEngine engine;
    private CompletableFuture<Void> termination;

    private DebuggerTestHarness(
            Path sourcePath,
            DebugEngine.Source source,
            Process process,
            BufferedReader output
    ) {
        this.sourcePath = sourcePath;
        this.sourceText = source.contents();
        this.sourceUri = source.uri();
        this.source = source;
        this.process = process;
        this.output = output;
        installEngine();
    }

    public static DebuggerTestHarness launch(Class<?> mainClass) throws Exception {
        Path sourcePath = sourcePath(mainClass);
        String sourceText = Files.readString(sourcePath, StandardCharsets.UTF_8);
        return launch(
                mainClass,
                new DebugEngine.Source(sourcePath.toUri(), mainClass.getName(), sourceText),
                true
        );
    }

    public static DebuggerTestHarness launch(Class<?> mainClass, DebugEngine.Source source) throws Exception {
        return launch(mainClass, source, true);
    }

    public static DebuggerTestHarness launchRunningByProcessId(Class<?> mainClass) throws Exception {
        Path sourcePath = sourcePath(mainClass);
        String sourceText = Files.readString(sourcePath, StandardCharsets.UTF_8);
        return launch(
                mainClass,
                new DebugEngine.Source(sourcePath.toUri(), mainClass.getName(), sourceText),
                false,
                true
        );
    }

    private static DebuggerTestHarness launch(
            Class<?> mainClass,
            DebugEngine.Source source,
            boolean initiallySuspended
    ) throws Exception {
        return launch(mainClass, source, initiallySuspended, false);
    }

    private static DebuggerTestHarness launch(
            Class<?> mainClass,
            DebugEngine.Source source,
            boolean initiallySuspended,
            boolean attachByProcessId
    ) throws Exception {
        Path sourcePath = sourcePath(mainClass);
        Process process = launchDebuggee(mainClass, initiallySuspended);
        BufferedReader output = new BufferedReader(new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8
        ));
        DebuggerTestHarness harness = new DebuggerTestHarness(
                sourcePath,
                source,
                process,
                output
        );
        try {
            int debugPort = harness.readDebugPort();
            DebugEngine.Target target = attachByProcessId
                    ? new LocalJvmDebugTargetResolver().resolve(
                            new DebugTargetDescriptor("debuggee", mainClass.getSimpleName(), process.pid()),
                            OPERATION_TIMEOUT
                    )
                    : DebugEngine.Target.local(debugPort, OPERATION_TIMEOUT);
            harness.engine.attach(target)
                    .get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return harness;
        } catch (Throwable failure) {
            try {
                harness.close();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public DebugEngine engine() {
        return this.engine;
    }

    public Path sourcePath() {
        return this.sourcePath;
    }

    public URI sourceUri() {
        return this.sourceUri;
    }

    public String sourceText() {
        return this.sourceText;
    }

    public int lineContaining(String marker) {
        String[] lines = this.sourceText.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException(
                "Missing source marker " + marker + " in " + this.sourcePath
                        + System.lineSeparator() + this.sourceText
        );
    }

    public List<DebugEngine.Breakpoint> setBreakpoints(DebugEngine.SourceBreakpoint... breakpoints)
            throws Exception {
        return this.engine.setBreakpoints(this.sourceUri, List.of(breakpoints))
                .get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    public void start() throws Exception {
        this.engine.start().get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    public void reattachByProcessId() throws Exception {
        this.engine.close();
        this.stops.clear();
        installEngine();
        DebugEngine.Target target = new LocalJvmDebugTargetResolver().resolve(
                new DebugTargetDescriptor("debuggee", this.source.binaryName(), this.process.pid()),
                OPERATION_TIMEOUT
        );
        this.engine.attach(target).get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    public DebugEngine.StoppedEvent awaitStop(String operation) throws InterruptedException {
        DebugEngine.StoppedEvent stop = this.stops.poll(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (stop == null) {
            throw new AssertionError("Debugger did not stop after " + operation);
        }
        return stop;
    }

    public DebugEngine.StackFrame firstFrame(long threadId) throws Exception {
        List<DebugEngine.StackFrame> frames = this.engine.stackTrace(threadId)
                .get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (frames.isEmpty()) {
            throw new AssertionError("Debugger returned no stack frames for thread " + threadId);
        }
        return frames.getFirst();
    }

    public DebugEngine.StackFrame applicationFrame(long threadId) throws Exception {
        return this.engine.stackTrace(threadId)
                .get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .stream()
                .filter(frame -> this.sourceUri.equals(frame.sourceUri()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Debugger returned no frame for " + this.sourceUri + " on thread " + threadId
                ));
    }

    public Map<String, DebugEngine.Variable> variables(DebugEngine.StackFrame frame) throws Exception {
        List<DebugEngine.Scope> scopes = this.engine.scopes(frame.id())
                .get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        DebugEngine.Scope localScope = scopes.stream()
                .filter(scope -> scope.name().equals("Local"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Debugger returned no local scope for frame " + frame.name()
                ));
        return variables(localScope.variablesReference());
    }

    public Map<String, DebugEngine.Variable> children(DebugEngine.Variable variable) throws Exception {
        if (variable.variablesReference() == 0) {
            throw new AssertionError("Variable " + variable.name() + " has no expandable children");
        }
        return variables(variable.variablesReference());
    }

    public DebugEngine.DebugThread mainThread() throws Exception {
        return this.engine.threads().get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .stream()
                .filter(thread -> thread.name().equals("main"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Debugger returned no main thread"));
    }

    public String readOutputLine(String operation) throws Exception {
        String line = CompletableFuture.supplyAsync(() -> {
            try {
                return this.output.readLine();
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }).get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (line == null) {
            throw new AssertionError("Debuggee output ended while waiting for " + operation);
        }
        return line;
    }

    public void closeInput() throws IOException {
        this.process.getOutputStream().close();
    }

    public int awaitExit() throws InterruptedException {
        if (!this.process.waitFor(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            throw new AssertionError("Debuggee did not exit within " + OPERATION_TIMEOUT);
        }
        return this.process.exitValue();
    }

    public void awaitDebuggerTermination() throws Exception {
        this.termination.get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
        Throwable failure = null;
        try {
            this.engine.close();
        } catch (Throwable exception) {
            failure = exception;
        }

        try {
            this.process.getOutputStream().close();
        } catch (Throwable exception) {
            failure = append(failure, exception);
        }
        try {
            this.output.close();
        } catch (Throwable exception) {
            failure = append(failure, exception);
        }

        if (this.process.isAlive()) {
            this.process.destroy();
            if (!this.process.waitFor(2, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                    failure = append(failure, new IllegalStateException("Could not stop debuggee process"));
                }
            }
        }

        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private Map<String, DebugEngine.Variable> variables(int variablesReference) throws Exception {
        Map<String, DebugEngine.Variable> result = new LinkedHashMap<>();
        for (DebugEngine.Variable variable : this.engine.variables(variablesReference)
                .get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            DebugEngine.Variable duplicate = result.put(variable.name(), variable);
            if (duplicate != null) {
                throw new AssertionError("Debugger returned duplicate variable " + variable.name());
            }
        }
        return result;
    }

    private void installEngine() {
        MicrosoftJavaDebugEngine replacement = new MicrosoftJavaDebugEngine();
        CompletableFuture<Void> engineTermination = new CompletableFuture<>();
        this.engine = replacement;
        this.termination = engineTermination;
        replacement.addListener(new DebugEngine.Listener() {
            @Override
            public void stopped(DebugEngine.StoppedEvent event) {
                stops.add(event);
            }

            @Override
            public void terminated() {
                engineTermination.complete(null);
            }
        });
        replacement.registerSource(this.source);
    }

    private int readDebugPort() throws Exception {
        String firstLine = readOutputLine("JDWP startup address");
        Matcher matcher = JDWP_ADDRESS.matcher(firstLine);
        if (!matcher.matches()) {
            throw new AssertionError("Unexpected JDWP startup output: " + firstLine);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Process launchDebuggee(Class<?> mainClass, boolean initiallySuspended) throws IOException {
        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"
        );
        Path testClasses;
        try {
            testClasses = Path.of(mainClass.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid test class location", exception);
        }
        return new ProcessBuilder(
                javaExecutable.toString(),
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend="
                        + (initiallySuspended ? "y" : "n")
                        + ",address=127.0.0.1:0",
                "-cp",
                testClasses.toString(),
                mainClass.getName()
        ).redirectErrorStream(true).start();
    }

    private static Path sourcePath(Class<?> fixtureClass) {
        return Path.of(System.getProperty("user.dir"), "src", "test", "java")
                .resolve(fixtureClass.getName().replace('.', '/') + ".java")
                .toAbsolutePath()
                .normalize();
    }

    private static Throwable append(Throwable failure, Throwable next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }
}
