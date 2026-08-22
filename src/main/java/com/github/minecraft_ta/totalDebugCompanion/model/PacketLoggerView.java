package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.PacketLoggerViewPanel;

import javax.swing.*;
import java.awt.*;

public class PacketLoggerView implements IEditorPanel {

    private PacketLoggerViewPanel packetLoggerViewPanel;

    public PacketLoggerView() {
        if (!CompanionApp.hasCapability(CompanionProtocol.CAPABILITY_PACKET_LOGGER)) {
            throw new IllegalStateException("Packet logging was not negotiated for this session");
        }
    }

    @Override
    public String getTitle() {
        return "Packet Logger";
    }

    @Override
    public String getTooltip() {
        return "Packet Logger";
    }

    @Override
    public Icon getIcon() {
        return Icons.UP_DOWN;
    }

    @Override
    public Component getComponent() {
        if (this.packetLoggerViewPanel == null) {
            this.packetLoggerViewPanel = new PacketLoggerViewPanel();
        }
        return this.packetLoggerViewPanel;
    }

    @Override
    public boolean canClose() {
        return this.packetLoggerViewPanel.canClose();
    }
}
