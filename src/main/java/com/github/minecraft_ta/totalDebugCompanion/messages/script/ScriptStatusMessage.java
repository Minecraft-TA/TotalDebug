package com.github.minecraft_ta.totalDebugCompanion.messages.script;

import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public class ScriptStatusMessage extends AbstractMessageIncoming {

    private int scriptId;
    private Type type;
    private String output;
    private String resultJson;
    private String error;

    public ScriptStatusMessage() {
    }

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.scriptId = messageStream.readInt();
        this.type = Type.valueOf(messageStream.readString());
        this.output = messageStream.readString();
        this.resultJson = messageStream.readBoolean() ? messageStream.readString() : null;
        this.error = messageStream.readString();
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.scriptId);
        messageStream.writeString(this.type.name());
        messageStream.writeString(this.output);
        messageStream.writeBoolean(this.resultJson != null);
        if (this.resultJson != null) {
            messageStream.writeString(this.resultJson);
        }
        messageStream.writeString(this.error);
    }

    public enum Type {
        COMPILATION_FAILED,
        COMPILATION_COMPLETED,
        RUN_EXCEPTION,
        RUN_COMPLETED;
    }

    public int getScriptId() {
        return scriptId;
    }

    public Type getType() {
        return type;
    }

    public String getOutput() {
        return this.output;
    }

    public String getResultJson() {
        return this.resultJson;
    }

    public String getError() {
        return this.error;
    }

    public String getMessage() {
        StringBuilder text = new StringBuilder();
        if (this.output != null && !this.output.isEmpty()) {
            text.append(this.output);
        }
        if (this.resultJson != null) {
            appendLine(text, this.resultJson);
        }
        if (this.error != null && !this.error.isEmpty()) {
            appendLine(text, this.error);
        }
        return text.toString();
    }

    private static void appendLine(StringBuilder text, String value) {
        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
            text.append(System.lineSeparator());
        }
        text.append(value);
    }
}
