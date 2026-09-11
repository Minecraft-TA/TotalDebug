package com.github.minecraft_ta.totalDebugCompanion.ui;

import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationService;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;

/** The application may run without a window; only these lifecycle operations cross into Swing. */
public interface CompanionUi {
    boolean prepareProjectSwitch();
    boolean closeProjectViews();
    boolean canExit();
    void setSwitching(boolean switching);
    void refreshProfile();
    void runtimeChanged();
    void setGameStatus(ServiceStatus status);
    void setMcpStatus(ServiceStatus status);
    void setRuntimeIndexStatus(RuntimeIndexService.Status status);
    void navigate(NavigationTarget target, NavigationService.Activation activation);
    void focus();
    void showError(String title, String message);
    void dispose();
}
