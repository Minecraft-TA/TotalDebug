package com.github.minecraft_ta.totaldebug.client.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.companion.CompanionAppClient;
import com.github.minecraft_ta.totaldebug.client.companion.message.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.script.ScriptCompilerClasspath;
import com.github.minecraft_ta.totaldebug.script.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.script.ScriptStatusType;
import com.github.minecraft_ta.totaldebug.tick.TickDomain;
import com.github.minecraft_ta.totaldebug.tick.TickTaskScheduler;

import java.io.IOException;
import java.util.Objects;

/** Owns client-side script runs initiated by the authenticated Companion process. */
public final class ClientScriptService implements AutoCloseable {
    private final CompanionAppClient companionApp;
    private final TickTaskScheduler tickTasks;
    private ScriptRunner runner;

    public ClientScriptService(CompanionAppClient companionApp, TickTaskScheduler tickTasks) {
        this.companionApp = Objects.requireNonNull(companionApp, "companionApp");
        this.tickTasks = Objects.requireNonNull(tickTasks, "tickTasks");
    }

    public void handleRunRequest(RunScriptMessage message) {
        Objects.requireNonNull(message, "message");
        if (message.serverSide()) {
            this.companionApp.sendScriptStatus(
                    message.scriptId(),
                    ScriptStatusType.COMPILATION_FAILED,
                    "Server-side scripts are not available yet"
            );
            return;
        }

        ScriptExecutionEnvironment environment;
        try {
            environment = ScriptExecutionEnvironment.fromWireName(message.executionEnvironment());
        } catch (IllegalArgumentException exception) {
            this.companionApp.sendScriptStatus(
                    message.scriptId(),
                    ScriptStatusType.COMPILATION_FAILED,
                    exception.getMessage()
            );
            return;
        }

        ScriptRunner activeRunner;
        try {
            activeRunner = runner();
        } catch (IOException | RuntimeException exception) {
            TotalDebug.LOGGER.error("Unable to prepare the live script compiler", exception);
            this.companionApp.sendScriptStatus(
                    message.scriptId(),
                    ScriptStatusType.COMPILATION_FAILED,
                    "Unable to prepare the live script compiler: " + exception.getMessage()
            );
            return;
        }
        activeRunner.runScript(message.scriptId(), message.scriptText(), environment);
    }

    public synchronized void stopScript(int scriptId) {
        if (this.runner != null) {
            this.runner.stopScript(scriptId);
        }
    }

    public synchronized void stopAll() {
        if (this.runner != null) {
            this.runner.stopAll();
        }
    }

    private synchronized ScriptRunner runner() throws IOException {
        if (this.runner != null) {
            return this.runner;
        }
        ScriptCompilerClasspath classpath = ScriptCompilerClasspath.discover();
        TotalDebug.LOGGER.info(
                "Resolved the live script compiler classpath from {} runtime sources using Java {} at {}",
                classpath.sources().size(),
                System.getProperty("java.version"),
                System.getProperty("java.home")
        );
        TotalDebug.LOGGER.debug("Live script compiler sources: {}", classpath.sources());
        this.runner = new ScriptRunner(
                classpath.argument(),
                TotalDebug.class.getClassLoader(),
                (phase, task) -> this.tickTasks.submit(TickDomain.CLIENT, phase, task),
                this.companionApp::sendScriptStatus
        );
        return this.runner;
    }

    @Override
    public synchronized void close() {
        if (this.runner != null) {
            this.runner.close();
            this.runner = null;
        }
    }
}
