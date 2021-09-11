package com.github.minecraft_ta.totaldebug.companionApp.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.messages.script.ScriptStatusMessage;
import com.github.minecraft_ta.totaldebug.network.script.ServerScriptStatusMessage;
import com.github.minecraft_ta.totaldebug.util.compiler.InMemoryJavaCompiler;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class ScriptRunner {

    private static final List<Script> runningScripts = new ObjectArrayList<>();

    public static void runScript(int id, String code, EntityPlayer owner) {
        String className = "ScriptClass" + id;
        String text =
                "public class " + className + " { public static void run() {" +
                code +
                "}}";

        boolean isServerSide = FMLCommonHandler.instance().getEffectiveSide() == Side.SERVER;

        Script script = new Script(id, CompletableFuture.runAsync(() -> {
            Class<?> scriptClass;
            try {
                scriptClass = InMemoryJavaCompiler.compile(className, text);
            } catch (InMemoryJavaCompiler.InMemoryCompilationFailedException e) {
                throw new CompletionException(e);
            }

            try {
                scriptClass.getMethod("run").invoke(null);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).exceptionally(exception -> {
            runningScripts.removeIf(s -> s.id == id && s.owner == owner);

            Throwable ex = exception.getCause();
            ScriptStatusMessage.Type type = ex instanceof InMemoryJavaCompiler.InMemoryCompilationFailedException ?
                    ScriptStatusMessage.Type.COMPILATION_FAILED : ScriptStatusMessage.Type.RUN_EXCEPTION;

            if (isServerSide) {
                TotalDebug.INSTANCE.network.sendTo(new ServerScriptStatusMessage(id, type, ex.getMessage()), (EntityPlayerMP) owner);
            } else {
                TotalDebug.PROXY.getCompanionApp().getClient().getMessageProcessor().enqueueMessage(new ScriptStatusMessage(id, type, ex.getMessage()));
            }
            return null;
        }), owner);

        runningScripts.add(script);
    }

    private static class Script {

        private final int id;
        //        private BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream()));
        private final CompletableFuture<Void> scriptTask;
        private final EntityPlayer owner;

        public Script(int id, CompletableFuture<Void> scriptTask, EntityPlayer owner) {
            this.id = id;
            this.scriptTask = scriptTask;
            this.owner = owner;
        }
    }
}
