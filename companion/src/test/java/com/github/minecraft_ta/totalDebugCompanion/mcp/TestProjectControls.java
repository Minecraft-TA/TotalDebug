package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.project.ProjectControls;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.runtime.IndexIdentity;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.ProjectRegistry;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Transport-only tests do not create an application or change projects. */
final class TestProjectControls implements ProjectControls {
    public List<ProjectRegistry.Project> projects() { return List.of(); }
    public CompanionProfile currentProject() { return null; }
    public boolean isSwitching() { return false; }
    public boolean isConnected() { return false; }
    public IndexIdentity.Kind indexSourceKind() { return null; }
    public RuntimeIndexService.Status getRuntimeIndexStatus() {
        return new RuntimeIndexService.Status(RuntimeIndexService.Phase.WAITING, "No project", null);
    }
    public CompletableFuture<Void> openProject(CompanionProfile profile, String name) { throw new AssertionError("Unexpected project open"); }
    public CompletableFuture<Void> renameProject(String id, String name) { throw new AssertionError("Unexpected project rename"); }
    public CompletableFuture<Void> forgetProject(String id) { throw new AssertionError("Unexpected project removal"); }
}
