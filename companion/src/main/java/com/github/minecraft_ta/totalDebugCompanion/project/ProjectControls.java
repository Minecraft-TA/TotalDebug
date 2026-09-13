package com.github.minecraft_ta.totalDebugCompanion.project;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.ProjectRegistry;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Application-level project selection shared by the window and MCP. */
public interface ProjectControls {
    List<ProjectRegistry.Project> projects();
    CompanionProfile currentProject();
    boolean isSwitching();
    boolean isConnected();
    RuntimeIndexService.Status getRuntimeIndexStatus();
    CompletableFuture<Void> openProject(CompanionProfile profile, String nameOverride);
    CompletableFuture<Void> renameProject(String id, String nameOverride);
    CompletableFuture<Void> forgetProject(String id);
}
