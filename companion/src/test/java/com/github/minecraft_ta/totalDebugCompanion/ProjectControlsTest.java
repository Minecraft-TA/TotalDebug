package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.mcp.ProjectSwitchJobs;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.session.ProjectRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ProjectControlsTest {
    @TempDir Path root;

    @Test void controlsUseTheirApplicationAndCapturedProjectScope() throws Exception {
        var config = new CompanionLaunchConfiguration(root.resolve("app"));
        try (var app = new CompanionApplication(config, "test");
             var other = new CompanionApplication(new CompanionLaunchConfiguration(root.resolve("other-app")), "other")) {
            var a = CompanionProfile.forGame(Files.createDirectories(root.resolve("ATM10/minecraft")));
            Files.createDirectory(a.workspaceDirectory().resolve("mods"));
            var b = CompanionProfile.forGame(Files.createDirectories(root.resolve("TotalDebug/run")));
            var server = app.startMcpServer(ProjectSwitchJobs.create(), 0);
            String endpoint = server.endpointUrl();
            try (var client = McpClient.sync(HttpClientStreamableHttpTransport.builder(endpoint.substring(0, endpoint.length() - 4))
                    .endpoint("/mcp").build()).build()) {
                client.initialize();
                Path unrelated = Files.createDirectory(root.resolve("unrelated"));
                var rejected = client.callTool(new McpSchema.CallToolRequest("project_open", Map.of("directory", unrelated.toString())));
                assertTrue(Boolean.TRUE.equals(rejected.isError()));
                assertNull(app.currentProject());
                assertTrue(app.projects().isEmpty());
                try (var children = Files.list(unrelated)) { assertEquals(0, children.count()); }
                var initialStatus = client.callTool(new McpSchema.CallToolRequest("status", Map.of()));
                assertFalse(Boolean.TRUE.equals(initialStatus.isError()));
                assertFalse(((Map<?, ?>) initialStatus.structuredContent()).containsKey("selected_project"));
                var emptyList = client.callTool(new McpSchema.CallToolRequest("project_list", Map.of()));
                assertEquals(List.of(), ((Map<?, ?>) emptyList.structuredContent()).get("projects"));
                var first = client.callTool(new McpSchema.CallToolRequest("project_open", Map.of("directory", a.workspaceDirectory().getParent().toString())));
                assertFalse(Boolean.TRUE.equals(first.isError()), first.toString());
                assertEquals(a, app.currentProject());
                client.callTool(new McpSchema.CallToolRequest("project_open", Map.of("directory", unrelated.toString())));
                assertEquals(a, app.currentProject(), "Rejected folders must preserve the selected project");
                other.openProject(b).join();
                var scopeA = app.requireProject();
                app.renameProject(a.id(), "My pack").join();
                assertSame(scopeA, app.requireProject(), "Renaming must preserve the scope and editor state");
                assertEquals("My pack", ProjectRegistry.open(config.paths()).projects().getFirst().name());
                app.renameProject(a.id(), null).join();
                assertEquals("ATM10", app.projects().getFirst().name());
                assertEquals(1, other.projects().size());
                var opened = client.callTool(new McpSchema.CallToolRequest("project_open", Map.of("directory", b.workspaceDirectory().toString())));
                assertFalse(Boolean.TRUE.equals(opened.isError()), opened.toString());
                assertEquals(b, app.currentProject());
                assertFalse(scopeA.isActive());
                var wrong = client.callTool(new McpSchema.CallToolRequest("client_code_execute", Map.of("code", "return 1;", "expected_project_id", a.id())));
                assertTrue(Boolean.TRUE.equals(wrong.isError()));
                assertTrue(wrong.structuredContent().toString().contains("expected_project_id"));
                var list = client.callTool(new McpSchema.CallToolRequest("project_list", Map.of()));
                assertEquals(2, ((List<?>) ((Map<?, ?>) list.structuredContent()).get("projects")).size());
                opened = client.callTool(new McpSchema.CallToolRequest("project_open", Map.of("project_id", a.id(), "name", "ATM")));
                assertFalse(Boolean.TRUE.equals(opened.isError()), opened.toString());
                assertEquals(a, app.currentProject());
                assertEquals("ATM", app.projects().getFirst().name());
                assertEquals(b, other.currentProject(), "MCP selection must not affect another application");
                app.forgetProject(b.id()).join();
                assertTrue(Files.isDirectory(b.dataDirectory()));
                assertEquals(1, app.projects().size());
            }
        }
    }
}
