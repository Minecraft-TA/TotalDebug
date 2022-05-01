package com.github.minecraft_ta.totaldebug.companionApp.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.messages.script.ExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.companionApp.messages.script.ScriptStatusMessage;
import com.github.minecraft_ta.totaldebug.network.CompanionAppForwardedMessage;
import com.github.minecraft_ta.totaldebug.util.compiler.InMemoryJavaCompiler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScriptRunner {

    private static int CLASS_ID = 0;

    private static final List<Script> runningScripts = new ArrayList<>();

    public static void runScript(int id, String code, EntityPlayer owner, ExecutionEnvironment executionEnvironment) {
        String className = extractClassName(code) + CLASS_ID++;
        String finalCode = code
                .replaceFirst("public class\\s+(.*?)\\s+extends\\s+BaseScript", "public class " + className + " extends BaseScript" + CLASS_ID)
                .replaceFirst("class\\s+BaseScript\\s+", "class BaseScript" + CLASS_ID);

        boolean isServerSide = FMLCommonHandler.instance().getSide() == Side.SERVER;
        boolean isAllowedToRun = !isServerSide ||
                                 (TotalDebug.INSTANCE.config.enableScripts &&
                                  (!TotalDebug.INSTANCE.config.enableScriptsOnlyForOp ||
                                   FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getOppedPlayers().getPermissionLevel(owner.getGameProfile()) >= 4));

        CompletableFuture.supplyAsync(() -> {
            if (!isAllowedToRun)
                throw new CompletionException(new CancellationException("Scripts are disabled or insufficient permissions"));

            Class<?> scriptClass;
            try {
                scriptClass = InMemoryJavaCompiler.compile(finalCode, className, "BaseScript" + CLASS_ID).get(1);
            } catch (InMemoryJavaCompiler.InMemoryCompilationFailedException e) {
                throw new CompletionException(e);
            }

            ScriptStatusMessage statusMessage = new ScriptStatusMessage(id, ScriptStatusMessage.Type.COMPILATION_COMPLETED, "");
            sendToClientOrCompanionApp(owner, isServerSide, statusMessage);

            return scriptClass;
        }).whenComplete((compiledClass, exception) -> {
            ScriptStatusMessage statusMessage;
            if (exception != null) {
                Throwable ex = exception.getCause();
                ScriptStatusMessage.Type type = ScriptStatusMessage.Type.COMPILATION_FAILED;

                statusMessage = new ScriptStatusMessage(id, type, ex.getMessage());
                sendToClientOrCompanionApp(owner, isServerSide, statusMessage);
            } else {
                CompletableFuture<String> future = new CompletableFuture<>();
                future.whenComplete((logOutput, ex) -> {
                    onScriptRunCompleted(id, owner, isServerSide, className, logOutput, ex);
                });

                Thread thread = new Thread(() -> {
                    try {
                        Object instance = compiledClass.newInstance();
                        compiledClass.getMethod("run").invoke(instance);

                        Field logWriterField = compiledClass.getSuperclass().getDeclaredField("logWriter");
                        logWriterField.setAccessible(true);
                        future.complete(logWriterField.get(instance).toString());
                    } catch (Throwable e) {
                        future.completeExceptionally(e);
                    }
                });
                thread.setName("Script Thread - " + compiledClass.getName());

                switch (executionEnvironment) {
                    case THREAD:
                        thread.start();
                        break;
                    case PRE_TICK:
                        TotalDebug.PROXY.addPreTickTask(() -> {
                            thread.start();
                            try {future.get();} catch (Throwable ignored) {}
                        });
                        break;
                    case POST_TICK:
                        TotalDebug.PROXY.addPostTickTask(() -> {
                            thread.start();
                            try {future.get();} catch (Throwable ignored) {}
                        });
                        break;
                }

                synchronized (runningScripts) {
                    runningScripts.add(new Script(owner, id, () -> {
                        future.cancel(true);
                        thread.interrupt();
                        thread.stop();
                    }));
                }
            }
        });
    }

    public static boolean isScriptRunning(int id, EntityPlayer owner) {
        return findScript(id, owner).isPresent();
    }

    public static void stopScript(int id, EntityPlayer owner) {
        synchronized (runningScripts) {
            findScript(id, owner).ifPresent(s -> s.cancel.run());
        }
    }

    public static void stopAllScripts(EntityPlayer player) {
        synchronized (runningScripts) {
            new ArrayList<>(runningScripts).stream().filter(s -> s.owner.getUniqueID().equals(player.getUniqueID())).forEach(s -> s.cancel.run());
        }
    }

    public static void stopAllScripts() {
        synchronized (runningScripts) {
            new ArrayList<>(runningScripts).forEach(s -> s.cancel.run());
        }
    }

    private static String extractClassName(String code) {
        Matcher matcher = Pattern.compile("class\\s+(.*?)\\s").matcher(code);
        if (!matcher.find())
            return "";

        return matcher.group(1);
    }

    private static Optional<Script> findScript(int id, EntityPlayer owner) {
        return runningScripts.stream().filter(s -> s.owner.getUniqueID().equals(owner.getUniqueID()) && s.ownerScriptId == id).findFirst();
    }

    private static void onScriptRunCompleted(int id, EntityPlayer owner, boolean isServerSide, String className, String logOutput, Throwable ex) {
        synchronized (runningScripts) {
            findScript(id, owner).ifPresent(runningScripts::remove);
        }

        ScriptStatusMessage message;
        if (ex != null) {
            if (ex.getCause() != null)
                ex = ex.getCause();
            message = new ScriptStatusMessage(id, ScriptStatusMessage.Type.RUN_EXCEPTION, ex instanceof CancellationException ? "Script run cancelled" : getShortenedStackTrace(ex, className));
        } else {
            message = new ScriptStatusMessage(id, ScriptStatusMessage.Type.RUN_COMPLETED, logOutput);
        }

        sendToClientOrCompanionApp(owner, isServerSide, message);
    }

    private static void sendToClientOrCompanionApp(EntityPlayer owner, boolean isServerSide, ScriptStatusMessage statusMessage) {
        if (isServerSide) {
            TotalDebug.INSTANCE.network.sendTo(new CompanionAppForwardedMessage(statusMessage), (EntityPlayerMP) owner);
        } else {
            TotalDebug.PROXY.getCompanionApp().getClient().getMessageProcessor().enqueueMessage(statusMessage);
        }
    }

    private static String getShortenedStackTrace(Throwable t, String className) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        t.printStackTrace(new PrintWriter(o, true));

        String stackTrace = new String(o.toByteArray(), StandardCharsets.UTF_8);
        int classIndex = stackTrace.lastIndexOf(className);
        if (classIndex == -1)
            return stackTrace;

        int nextNewLineIndex = stackTrace.indexOf('\n', classIndex);
        return stackTrace.substring(0, nextNewLineIndex);
    }

    private static final class Script {

        private final EntityPlayer owner;
        private final int ownerScriptId;
        private final Runnable cancel;

        private Script(EntityPlayer owner, int ownerScriptId, Runnable cancel) {
            this.owner = owner;
            this.ownerScriptId = ownerScriptId;
            this.cancel = cancel;
        }
    }
}
