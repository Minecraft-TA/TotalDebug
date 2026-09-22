package com.github.minecraft_ta.totalDebugCompanion.project;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.runtime.IndexIdentity;
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
    IndexIdentity.Kind indexSourceKind();
    CompletableFuture<Void> retryIndex();
    CompletableFuture<Void> reconnectGame(ProjectScope expectedProject);
    CompletableFuture<String> gameLaunchUnavailableReason(ProjectScope expectedProject);
    CompletableFuture<Void> launchGame(ProjectScope expectedProject);
    CompletableFuture<Void> openProject(CompanionProfile profile, String nameOverride);
    CompletableFuture<Void> renameProject(String id, String nameOverride);
    CompletableFuture<Void> forgetProject(String id);
}
