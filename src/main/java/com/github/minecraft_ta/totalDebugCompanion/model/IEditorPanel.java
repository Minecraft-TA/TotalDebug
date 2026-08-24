package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.BottomInformationBar;

import javax.swing.*;
import java.awt.*;

public interface IEditorPanel {

    String getTitle();

    String getTooltip();

    Icon getIcon();

    Component getComponent();

    default EditorLocation getLocation() {
        return EditorLocation.empty();
    }

    default BottomInformationBar getInformationBar() {
        return null;
    }

    default boolean canClose() {
        return true;
    }

    default void dispose() {
    }
}
