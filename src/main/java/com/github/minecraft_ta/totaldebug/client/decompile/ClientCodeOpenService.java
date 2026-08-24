package com.github.minecraft_ta.totaldebug.client.decompile;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.companion.CompanionAppClient;
import com.github.minecraft_ta.totaldebug.config.TotalDebugConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClientCodeOpenService {
    private final CompanionAppClient companionApp;
    private final Map<String, CompletableFuture<Void>> inFlightRequests = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
            .daemon()
            .name("TotalDebug Companion requests")
            .unstarted(task));

    public ClientCodeOpenService(CompanionAppClient companionApp) {
        this.companionApp = Objects.requireNonNull(companionApp, "companionApp");
    }

    public CompletableFuture<Void> openClass(Class<?> targetClass) {
        return openClass(targetClass, SourceTarget.wholeClass());
    }

    public CompletableFuture<Void> openClass(Class<?> targetClass, SourceTarget sourceTarget) {
        Objects.requireNonNull(targetClass, "targetClass");
        Objects.requireNonNull(sourceTarget, "sourceTarget");
        String binaryName = targetClass.getName();

        if (!TotalDebugConfig.CLIENT.useCompanionApp.get()) {
            showMessage(Component.literal("TotalDebug Companion is disabled in the client config")
                    .withStyle(ChatFormatting.YELLOW));
            return CompletableFuture.completedFuture(null);
        }

        synchronized (this.inFlightRequests) {
            CompletableFuture<Void> existing = this.inFlightRequests.get(binaryName);
            if (existing != null && !existing.isDone()) {
                return existing;
            }

            showMessage(Component.literal("Opening " + binaryName + "...").withStyle(ChatFormatting.GRAY));
            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                try {
                    this.companionApp.openClassAndFocus(binaryName, sourceTarget, ClientCodeOpenService::releaseGameInput);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }, this.worker);
            this.inFlightRequests.put(binaryName, task);
            task.whenComplete((ignored, failure) -> {
                try {
                    if (failure == null) {
                        showMessage(Component.literal("Companion is opening " + binaryName)
                                .withStyle(ChatFormatting.GRAY));
                    } else {
                        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                                ? failure.getCause()
                                : failure;
                        TotalDebug.LOGGER.error("Unable to open {} in Companion", binaryName, cause);
                        String detail = cause.getMessage() == null
                                ? cause.getClass().getSimpleName()
                                : cause.getMessage();
                        showMessage(Component.literal("TotalDebug: " + detail).withStyle(ChatFormatting.RED));
                    }
                } finally {
                    synchronized (this.inFlightRequests) {
                        this.inFlightRequests.remove(binaryName, task);
                    }
                }
            });
            return task;
        }
    }

    public CompletableFuture<Void> focusCompanion() {
        if (!TotalDebugConfig.CLIENT.useCompanionApp.get()) {
            showMessage(Component.literal("TotalDebug Companion is disabled in the client config")
                    .withStyle(ChatFormatting.YELLOW));
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            try {
                this.companionApp.focus(ClientCodeOpenService::releaseGameInput);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, this.worker);
        task.exceptionally(failure -> {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            TotalDebug.LOGGER.error("Unable to focus Companion", cause);
            showMessage(Component.literal("TotalDebug: " + cause.getMessage()).withStyle(ChatFormatting.RED));
            return null;
        });
        return task;
    }

    private static void releaseGameInput() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.submit(minecraft.mouseHandler::releaseMouse).join();
    }

    private static void showMessage(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(message, false);
            }
        });
    }
}
