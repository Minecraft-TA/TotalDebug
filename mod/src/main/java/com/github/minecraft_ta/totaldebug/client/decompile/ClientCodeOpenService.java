package com.github.minecraft_ta.totaldebug.client.decompile;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.companion.CompanionAppClient;
import com.github.minecraft_ta.totaldebug.config.TotalDebugConfig;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;
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

    public void openClass(Class<?> targetClass) {
        openClass(targetClass, SourceTarget.wholeClass());
    }

    public void openClass(Class<?> targetClass, SourceTarget sourceTarget) {
        Objects.requireNonNull(targetClass, "targetClass");
        Objects.requireNonNull(sourceTarget, "sourceTarget");
        String binaryName = targetClass.getName();
        request(binaryName, "Opening " + binaryName, "Companion is opening " + binaryName,
                () -> this.companionApp.openClassAndFocus(binaryName, sourceTarget,
                        ClientCodeOpenService::releaseGameInput));
    }

    public void inspect(InspectSubjectPayload subject) {
        Objects.requireNonNull(subject, "subject");
        request(subject.subject(), "Inspecting " + subject.identity().title(),
                "Companion is inspecting " + subject.identity().title(),
                () -> this.companionApp.inspectAndFocus(subject, ClientCodeOpenService::releaseGameInput));
    }

    private void request(String key, String started, String sent, CompanionRequest action) {
        if (!TotalDebugConfig.CLIENT.useCompanionApp.get()) {
            showMessage(Component.literal("TotalDebug Companion is disabled in the client config")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }

        synchronized (this.inFlightRequests) {
            CompletableFuture<Void> existing = this.inFlightRequests.get(key);
            if (existing != null && !existing.isDone()) {
                return;
            }

            showMessage(Component.literal(started + "...").withStyle(ChatFormatting.GRAY));
            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                try {
                    action.run();
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }, this.worker);
            this.inFlightRequests.put(key, task);
            task.whenComplete((ignored, failure) -> {
                try {
                    if (failure == null) {
                        showMessage(Component.literal(sent).withStyle(ChatFormatting.GRAY));
                    } else {
                        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                                ? failure.getCause()
                                : failure;
                        TotalDebug.LOGGER.error("Companion request for {} failed", key, cause);
                        String detail = cause.getMessage() == null
                                ? cause.getClass().getSimpleName()
                                : cause.getMessage();
                        showMessage(Component.literal("TotalDebug: " + detail).withStyle(ChatFormatting.RED));
                    }
                } finally {
                    synchronized (this.inFlightRequests) {
                        this.inFlightRequests.remove(key, task);
                    }
                }
            });
        }
    }

    @FunctionalInterface
    private interface CompanionRequest {
        void run() throws IOException;
    }

    public void focusCompanion() {
        if (!TotalDebugConfig.CLIENT.useCompanionApp.get()) {
            showMessage(Component.literal("TotalDebug Companion is disabled in the client config")
                    .withStyle(ChatFormatting.YELLOW));
            return;
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
