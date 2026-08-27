package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.ScriptStatusMessage;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ScriptPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.BottomInformationBar;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ScriptView implements IEditorPanel {

    private final String text;
    private final Path path;
    protected ScriptPanel scriptPanel;

    public ScriptView(String scriptName) {
        if (!CompanionApp.supportsCapability(CompanionProtocol.CAPABILITY_SCRIPT_EXECUTION)) {
            throw new IllegalStateException("Script execution was not negotiated for this session");
        }
        this.path = CompanionApp.getRootPath().resolve("scripts").resolve(scriptName + ".java");
        try {
            if (!Files.exists(this.path)) {
                this.text = """
                        public class %s extends BaseScript {
                        \t@Override
                        \tpublic void run() throws Throwable {
                        \t\t
                        \t}
                        }
                        """.formatted(scriptName);
                Files.writeString(this.path, this.text);
            } else {
                this.text = Files.readString(this.path);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean canClose() {
        var result = this.scriptPanel.canSave();
        if (result)
            CompanionApp.SERVER.getMessageBus().unregister(ScriptStatusMessage.class, this.scriptPanel);

        return result;
    }

    public String getSourceText() {
        return text;
    }

    public Path getPath() {
        return path;
    }

    public void centerViewportOnOffset(int offset) {
        ((ScriptPanel) getComponent()).centerViewportOnOffset(offset);
    }

    @Override
    public String getTitle() {
        return this.path.getFileName().toString();
    }

    @Override
    public String getTooltip() {
        return null;
    }

    @Override
    public Icon getIcon() {
        return Icons.JAVA_FILE;
    }

    @Override
    public Component getComponent() {
        if (this.scriptPanel == null)
            this.scriptPanel = new ScriptPanel(this);
        return this.scriptPanel;
    }

    @Override
    public EditorLocation getLocation() {
        return new EditorLocation("Scripts", java.util.List.of(this.path.getFileName().toString()), this.path.toString());
    }

    @Override
    public BottomInformationBar getInformationBar() {
        return this.scriptPanel == null ? null : this.scriptPanel.getBottomInformationBar();
    }

    @Override
    public void dispose() {
        if (this.scriptPanel != null) {
            this.scriptPanel.dispose();
        }
    }
}
