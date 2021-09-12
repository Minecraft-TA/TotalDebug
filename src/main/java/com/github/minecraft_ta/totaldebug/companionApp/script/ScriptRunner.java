package com.github.minecraft_ta.totaldebug.companionApp.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.messages.script.ScriptStatusMessage;
import com.github.minecraft_ta.totaldebug.network.CompanionAppForwardedMessage;
import com.github.minecraft_ta.totaldebug.util.compiler.InMemoryJavaCompiler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class ScriptRunner {

    private static int CLASS_ID = 0;

    public static void runScript(int id, String code, EntityPlayer owner) {
        String className = "ScriptClass" + CLASS_ID++;
        String text =
                "public class " + className + " { " +
                "   public void run() throws Throwable {" +
                "   " + code +
                "   }" +
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
            if (isServerSide) {
                TotalDebug.INSTANCE.network.sendTo(new CompanionAppForwardedMessage(statusMessage), (EntityPlayerMP) owner);
            } else {
                TotalDebug.PROXY.getCompanionApp().getClient().getMessageProcessor().enqueueMessage(statusMessage);
            }

            try {
                Object instance = scriptClass.newInstance();
                scriptClass.getMethod("run").invoke(instance);

                return scriptClass.getField("logWriter").get(instance).toString();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).whenComplete((logOutput, exception) -> {
            ScriptStatusMessage statusMessage;
            if (exception != null) {
                Throwable ex = exception.getCause();
                ScriptStatusMessage.Type type = ex instanceof InMemoryJavaCompiler.InMemoryCompilationFailedException ?
                        ScriptStatusMessage.Type.COMPILATION_FAILED : ScriptStatusMessage.Type.RUN_EXCEPTION;

                statusMessage = new ScriptStatusMessage(id, type, ex.getMessage());
            } else {
                statusMessage = new ScriptStatusMessage(id, ScriptStatusMessage.Type.RUN_COMPLETED, logOutput);
            }

            if (isServerSide) {
                TotalDebug.INSTANCE.network.sendTo(new CompanionAppForwardedMessage(statusMessage), (EntityPlayerMP) owner);
            } else {
                TotalDebug.PROXY.getCompanionApp().getClient().getMessageProcessor().enqueueMessage(statusMessage);
            }
        });
    }
}
