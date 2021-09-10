package com.github.minecraft_ta.totaldebug.companionApp.messages.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.util.compiler.InMemoryJavaCompiler;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

import java.util.concurrent.ThreadLocalRandom;

public class RunScriptMessage extends AbstractMessageIncoming {

    private String scriptText;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.scriptText = messageStream.readString();
    }

    public static void handle(RunScriptMessage message) {
        String className = "ScriptClass" + ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        String text =
                "public class " + className + " { public static void run() {" +
                message.scriptText +
                "}}";

        Class<?> scriptClass = InMemoryJavaCompiler.compile(className, text, RunScriptMessage.class.getClassLoader());
        if (scriptClass == null) {
            TotalDebug.LOGGER.error("Compilation failed for code:\n" + text);
            return;
        }

        try {
            scriptClass.getMethod("run").invoke(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
