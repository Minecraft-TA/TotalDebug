package com.github.minecraft_ta.totalDebugCompanion.messages.script;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.util.TextUtils;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import javax.swing.*;

public class RunScriptMessage extends AbstractMessageOutgoing {

    private final int scriptId;
    private final String scriptText;
    private final boolean serverSide;
    private final ExecutionEnvironment executionEnvironment;

    public RunScriptMessage(int scriptId, String scriptText, boolean serverSide, ExecutionEnvironment executionEnvironment) {
        this.scriptId = scriptId;
        this.scriptText = scriptText;
        this.serverSide = serverSide;
        this.executionEnvironment = executionEnvironment;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.scriptId);
        messageStream.writeString(this.scriptText);
        messageStream.writeBoolean(this.serverSide);
        messageStream.writeString(executionEnvironment.name());
    }

    public enum ExecutionEnvironment {
        THREAD(TextUtils.htmlPrimarySecondaryString("Thread", " - ", "Run separately; Stop is cooperative"), null),
        PRE_TICK(TextUtils.htmlPrimarySecondaryString("Pre Tick", " - ", "Run on the main thread; cannot be force-stopped"), Icons.WARNING),
        POST_TICK(TextUtils.htmlPrimarySecondaryString("Post Tick", " - ", "Run on the main thread; cannot be force-stopped"), Icons.WARNING);

        private final String label;
        private final Icon icon;

        ExecutionEnvironment(String label, Icon icon) {
            this.label = label;
            this.icon = icon;
        }

        public String getLabel() {
            return label;
        }

        public Icon getIcon() {
            return this.icon;
        }
    }
}
