package com.github.minecraft_ta.totaldebug.client;

import com.github.minecraft_ta.totaldebug.client.companion.CompanionAppClient;
import com.github.minecraft_ta.totaldebug.client.companion.CompanionProgressActionBar;
import com.github.minecraft_ta.totaldebug.client.decompile.ClientDecompilationService;
import com.github.minecraft_ta.totaldebug.client.decompile.DecompilationRequest;
import com.github.minecraft_ta.totaldebug.client.decompile.SourceTarget;
import com.github.minecraft_ta.totaldebug.client.input.CodeViewInput;
import com.github.minecraft_ta.totaldebug.client.script.ClientScriptService;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Owns the client-only runtime assembled after Minecraft has reached client setup. */
public final class TotalDebugClient {
    private static volatile TotalDebugClient instance;

    private final ClientDecompilationService decompilation;
    private final OpenCodeOperation openCode;
    private final CodeViewInput codeViewInput;
    private final ClientScriptService scripts;

    private TotalDebugClient(Path gameDirectory) {
        Path totalDebugDirectory = gameDirectory
                .resolve("total-debug")
                .toAbsolutePath()
                .normalize();
        CompanionAppClient companionApp = new CompanionAppClient(totalDebugDirectory);
        companionApp.setProgressListener(progress -> CompanionProgressActionBar.show(Minecraft.getInstance(), progress));
        this.decompilation = new ClientDecompilationService(companionApp);
        this.openCode = new OpenCodeOperation(new OpenCodeOperation.Actions() {
            @Override
            public void openClass(Class<?> targetClass) {
                TotalDebugClient.this.decompilation.openClass(targetClass);
            }

            @Override
            public void focusCompanion() {
                TotalDebugClient.this.decompilation.focusCompanion();
            }
        });
        this.codeViewInput = new CodeViewInput(this::openOrFocus);
        this.scripts = new ClientScriptService(companionApp, TotalDebug.get().tickTasks());
        companionApp.setDecompileRequestHandler(this.decompilation::openNamedClass);
        companionApp.setScriptRequestHandler(this.scripts::handleRunRequest);
        companionApp.setStopScriptHandler(this.scripts::stopScript);
        companionApp.setSessionClosedHandler(this.scripts::close);
    }

    public static synchronized void initialize(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        if (instance != null) {
            throw new IllegalStateException("TotalDebug client was initialized more than once");
        }
        instance = new TotalDebugClient(minecraft.gameDirectory.toPath());
    }

    public static TotalDebugClient get() {
        TotalDebugClient current = instance;
        if (current == null) {
            throw new IllegalStateException("TotalDebug client has not been initialized yet");
        }
        return current;
    }

    public static Optional<TotalDebugClient> current() {
        return Optional.ofNullable(instance);
    }

    public void openOrFocus(Optional<Class<?>> targetClass) {
        this.openCode.openOrFocus(targetClass);
    }

    public void openClass(Class<?> targetClass) {
        this.decompilation.open(new DecompilationRequest(targetClass, SourceTarget.wholeClass()));
    }

    public CodeViewInput codeViewInput() {
        return this.codeViewInput;
    }

    public void stopAllScripts() {
        this.scripts.stopAll();
    }
}
