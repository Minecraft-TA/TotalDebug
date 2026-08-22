package com.github.minecraft_ta.totaldebug.client.decompile;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.bytecode.ClassLoaderBytecodeSource;
import com.github.minecraft_ta.totaldebug.client.companion.CompanionAppClient;
import com.github.minecraft_ta.totaldebug.config.TotalDebugConfig;
import com.github.minecraft_ta.totaldebug.decompiler.DecompilationResult;
import com.github.minecraft_ta.totaldebug.decompiler.DecompilerDiagnostic;
import com.github.minecraft_ta.totaldebug.decompiler.JavaDecompiler;
import com.github.minecraft_ta.totaldebug.decompiler.VineflowerDecompiler;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClientDecompilationService {
    private final CompanionAppClient companionApp;
    private final JavaDecompiler javaDecompiler;
    private final Path decompilationDirectory;
    private final Map<DecompilationRequest, CompletableFuture<Path>> inFlightRequests = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "TotalDebug decompiler");
        thread.setDaemon(true);
        return thread;
    });

    public ClientDecompilationService(CompanionAppClient companionApp) {
        this(companionApp, new VineflowerDecompiler());
    }

    ClientDecompilationService(CompanionAppClient companionApp, JavaDecompiler javaDecompiler) {
        this.companionApp = Objects.requireNonNull(companionApp, "companionApp");
        this.javaDecompiler = Objects.requireNonNull(javaDecompiler, "javaDecompiler");
        this.decompilationDirectory = companionApp.dataDirectory().resolve("decompiled-files");
    }

    public CompletableFuture<Path> openClass(Class<?> targetClass) {
        return open(new DecompilationRequest(targetClass, SourceTarget.wholeClass()));
    }

    public CompletableFuture<Path> open(DecompilationRequest request) {
        Objects.requireNonNull(request, "request");
        Class<?> targetClass = request.targetClass();

        CompletableFuture<Path> task;
        synchronized (this.inFlightRequests) {
            CompletableFuture<Path> existingTask = this.inFlightRequests.get(request);
            if (existingTask != null && !existingTask.isDone()) {
                return existingTask;
            }

            showMessage(Component.literal("Decompiling " + targetClass.getName() + "...")
                    .withStyle(ChatFormatting.GRAY));
            task = CompletableFuture.supplyAsync(() -> {
                try {
                    Path sourceFile = decompile(targetClass);
                    if (TotalDebugConfig.CLIENT.useCompanionApp.get()) {
                        this.companionApp.open(sourceFile, request.sourceTarget());
                    }
                    return sourceFile;
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }, this.worker);
            this.inFlightRequests.put(request, task);
        }

        task.whenComplete((sourceFile, failure) -> {
            try {
                if (failure == null) {
                    if (TotalDebugConfig.CLIENT.useCompanionApp.get()) {
                        Component fileName = Component.literal(sourceFile.getFileName().toString())
                                .withStyle(ChatFormatting.WHITE);
                        showMessage(Component.translatable("companion_app.open_file", fileName)
                                .withStyle(ChatFormatting.GRAY));
                    } else {
                        showMessage(Component.literal("Decompiled " + sourceFile).withStyle(ChatFormatting.GRAY));
                    }
                } else {
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                            ? failure.getCause()
                            : failure;
                    TotalDebug.LOGGER.error("Unable to decompile {}", targetClass.getName(), cause);
                    String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                    showMessage(Component.literal("TotalDebug: " + detail).withStyle(ChatFormatting.RED));
                }
            } finally {
                synchronized (this.inFlightRequests) {
                    this.inFlightRequests.remove(request, task);
                }
            }
        });
        return task;
    }

    public void openNamedClass(String binaryName, SourceTarget sourceTarget) {
        Objects.requireNonNull(binaryName, "binaryName");
        Objects.requireNonNull(sourceTarget, "sourceTarget");
        try {
            Class<?> targetClass = Class.forName(binaryName, false, TotalDebug.class.getClassLoader());
            open(new DecompilationRequest(targetClass, sourceTarget));
        } catch (ClassNotFoundException | LinkageError failure) {
            TotalDebug.LOGGER.error("The companion requested unknown runtime class {}", binaryName, failure);
            showMessage(Component.literal("TotalDebug: unknown runtime class " + binaryName)
                    .withStyle(ChatFormatting.RED));
        }
    }

    public CompletableFuture<Void> focusCompanion() {
        if (!TotalDebugConfig.CLIENT.useCompanionApp.get()) {
            showMessage(Component.literal("TotalDebug companion integration is disabled in the client config")
                    .withStyle(ChatFormatting.YELLOW));
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            try {
                this.companionApp.focus();
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, this.worker);
        task.exceptionally(failure -> {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            TotalDebug.LOGGER.error("Unable to focus the companion app", cause);
            showMessage(Component.literal("TotalDebug: " + cause.getMessage()).withStyle(ChatFormatting.RED));
            return null;
        });
        return task;
    }

    private Path decompile(Class<?> targetClass) throws IOException {
        Files.createDirectories(this.decompilationDirectory);
        ClassLoaderBytecodeSource bytecodeSource = ClassLoaderBytecodeSource.forClass(targetClass);
        URL resource = bytecodeSource.findClassResource(targetClass.getName());
        if (resource == null) {
            throw new IOException("The defining class loader cannot provide " + targetClass.getName() + ".class");
        }

        ClassLoader definingLoader = bytecodeSource.definingClassLoader();
        TotalDebug.LOGGER.info(
                "Decompiling {} from {} using defining loader {}",
                targetClass.getName(),
                resource,
                definingLoader == null ? "bootstrap" : definingLoader
        );
        DecompilationResult result = this.javaDecompiler.decompile(targetClass.getName(), bytecodeSource);
        for (DecompilerDiagnostic diagnostic : result.diagnostics()) {
            if (diagnostic.severity() == DecompilerDiagnostic.Severity.ERROR) {
                TotalDebug.LOGGER.error(
                        "Vineflower error while decompiling {}: {}",
                        targetClass.getName(),
                        diagnostic.message()
                );
            } else {
                TotalDebug.LOGGER.warn(
                        "Vineflower warning while decompiling {}: {}",
                        targetClass.getName(),
                        diagnostic.message()
                );
            }
        }
        if (!result.isComplete()) {
            throw new IOException("Vineflower produced only partial source for " + targetClass.getName());
        }

        Path output = this.decompilationDirectory.resolve(targetClass.getName() + ".java");
        Files.writeString(
                output,
                result.source(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        return output;
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
