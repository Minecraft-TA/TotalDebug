package com.github.minecraft_ta.totaldebug.client;

import com.github.minecraft_ta.totaldebug.client.companion.CompanionAppClient;
import com.github.minecraft_ta.totaldebug.client.companion.CompanionProgressActionBar;
import com.github.minecraft_ta.totaldebug.client.decompile.ClientCodeOpenService;
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

    private final ClientCodeOpenService codeOpen;
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
        this.codeOpen = new ClientCodeOpenService(companionApp);
        this.openCode = new OpenCodeOperation(new OpenCodeOperation.Actions() {
            @Override
            public void openClass(Class<?> targetClass) {
                TotalDebugClient.this.codeOpen.openClass(targetClass);
            }

            @Override
            public void focusCompanion() {
                TotalDebugClient.this.codeOpen.focusCompanion();
            }
        });
        this.codeViewInput = new CodeViewInput(this::openOrFocus);
        this.scripts = new ClientScriptService(companionApp, TotalDebug.get().tickTasks());
        TotalDebug.get().network().installForwardedCompanionReceiver(this.scripts::handleForwardedPayload);
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
        this.codeOpen.openClass(targetClass);
    }

    public CodeViewInput codeViewInput() {
        return this.codeViewInput;
    }

    public void onServerDisconnect() {
        this.scripts.onServerDisconnect();
    }
}
