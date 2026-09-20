package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.mcp.ProjectSwitchJobs;
import com.github.minecraft_ta.totalDebugCompanion.model.ServiceStatus;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpLifecycleTest {
    @TempDir Path root;

    @Test void repeatedEnableKeepsListenerAndDisableFromEdtClosesIt() throws Exception {
        var app = new CompanionApplication(new CompanionLaunchConfiguration(root), "test");
        try (app; var client = HttpClient.newHttpClient()) {
            var server = app.startMcpServer(ProjectSwitchJobs.create(), 0);
            String endpoint = server.endpointUrl();
            app.setMcpEnabled(true).get(10, TimeUnit.SECONDS);
            assertEquals(endpoint, status(app).detail());
            assertEquals(ServiceStatus.State.AVAILABLE, status(app).state());
            var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(2)).GET().build();
            assertNotNull(client.send(request, HttpResponse.BodyHandlers.discarding()));
            var stopped = new AtomicReference<CompletableFuture<Void>>();
            var busy = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var workerField = CompanionApplication.class.getDeclaredField("projectWorker");
            workerField.setAccessible(true);
            ((ExecutorService) workerField.get(app)).execute(() -> {
                busy.countDown();
                try { release.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            assertTrue(busy.await(5, TimeUnit.SECONDS));
            try {
                SwingUtilities.invokeAndWait(() -> stopped.set(app.setMcpEnabled(false)));
                stopped.get().get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            assertEquals(ServiceStatus.State.INACTIVE, status(app).state());
            assertThrows(Exception.class, () -> client.send(request, HttpResponse.BodyHandlers.discarding()));
            app.setMcpEnabled(false).get(10, TimeUnit.SECONDS);
            assertEquals(ServiceStatus.State.INACTIVE, status(app).state());
        }
        assertTrue(app.setMcpEnabled(true).isCompletedExceptionally());
    }

    private static ServiceStatus status(CompanionApplication app) throws Exception {
        var field = CompanionApplication.class.getDeclaredField("mcpStatus");
        field.setAccessible(true);
        return (ServiceStatus) field.get(app);
    }
}
