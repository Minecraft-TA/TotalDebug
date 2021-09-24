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
import org.apache.commons.lang3.tuple.Pair;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
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

    private static final Pattern IMPORT_PATTERN = Pattern.compile("import\\s(.*?);");

    private static int CLASS_ID = 0;

    private static final List<Script> runningScripts = new ArrayList<>();

    public static void runScript(int id, String code, EntityPlayer owner, ExecutionEnvironment executionEnvironment) {
        Pair<String, String> importsCodePair = extractImports(code);

        String className = "ScriptClass" + CLASS_ID++;
        String text =
                importsCodePair.getLeft() +
                "public class " + className + " {\n" +
                "   public void run() throws Throwable {\n" +
                "   " + importsCodePair.getRight() + "\n" +
                "   }\n" +
                "   public java.io.StringWriter logWriter = new java.io.StringWriter();" +
                "   public void logln(String s) {" +
                "       this.log(String.format(\"%s%n\", s));" +
                "   }" +
                "   public void log(String s) {" +
                "       this.logWriter.append(s);" +
                "   }" +
                "}";

        boolean isServerSide = FMLCommonHandler.instance().getEffectiveSide() == Side.SERVER;

        CompletableFuture.supplyAsync(() -> {
            Class<?> scriptClass;
            try {
                scriptClass = InMemoryJavaCompiler.compile(className, text);
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
                //TODO: switch over execution environment
                CompletableFuture<String> future = new CompletableFuture<>();
                Thread thread = new Thread(() -> {
                    try {
                        Object instance = compiledClass.newInstance();
                        compiledClass.getMethod("run").invoke(instance);

                        future.complete(compiledClass.getField("logWriter").get(instance).toString());
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

                future.whenComplete((logOutput, ex) -> {
                    onScriptRunCompleted(id, owner, isServerSide, logOutput, ex);
                });

                synchronized (runningScripts) {
                    runningScripts.add(new Script(owner, id, () -> {
                        future.cancel(true);
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

    private static Optional<Script> findScript(int id, EntityPlayer owner) {
        return runningScripts.stream().filter(s -> s.owner.getUniqueID().equals(owner.getUniqueID()) && s.ownerScriptId == id).findFirst();
    }

    private static void onScriptRunCompleted(int id, EntityPlayer owner, boolean isServerSide, String logOutput, Throwable ex) {
        synchronized (runningScripts) {
            findScript(id, owner).ifPresent(runningScripts::remove);
        }

        ScriptStatusMessage message;
        if (ex != null) {
            if (ex.getCause() != null)
                ex = ex.getCause();
            message = new ScriptStatusMessage(id, ScriptStatusMessage.Type.RUN_EXCEPTION, ex instanceof CancellationException ? "Script run cancelled" : getShortenedStackTrace(ex));
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

    private static Pair<String, String> extractImports(String code) {
        Matcher matcher = IMPORT_PATTERN.matcher(code);

        StringBuilder imports = new StringBuilder();
        while (matcher.find()) {
            imports.append(matcher.group());
        }

        return Pair.of(imports.toString(), code.replaceAll(IMPORT_PATTERN.pattern() + "(\\r\\n|\\r|\\n)", ""));
    }

    private static String getShortenedStackTrace(Throwable t) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        t.printStackTrace(new PrintWriter(o, true));

        String stackTrace = new String(o.toByteArray(), StandardCharsets.UTF_8);
        int classIndex = stackTrace.indexOf("ScriptClass");
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
