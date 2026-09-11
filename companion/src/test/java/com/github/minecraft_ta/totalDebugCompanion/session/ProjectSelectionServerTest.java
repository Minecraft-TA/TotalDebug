package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.ProjectSelectionRequest;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ClientHelloMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ProjectSelectionServerTest {
    @Test void requiresAuthenticationAndAnExplicitNonBrowserRequest() throws Exception {
        var selected = new AtomicReference<String>("a");
        try (var server = new ProjectSelectionServer(new SessionAuthenticator("secret"), hello -> selected.set(hello.profileId()), () -> false);
             var client = HttpClient.newHttpClient()) {
            assertThrows(IOException.class, () -> ProjectSelectionRequest.send(server.port(), hello("wrong", "b")));
            assertEquals("a", selected.get());
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + ProjectSelectionRequest.PATH))
                    .header("Content-Type", "application/octet-stream").header("Origin", "https://example.org")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(ProjectSelectionRequest.encode(hello("secret", "b")))).build();
            assertEquals(400, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals("a", selected.get());
            assertFalse(ProjectSelectionRequest.send(server.port(), hello("secret", "b")));
            assertEquals("b", selected.get());
        }
    }

    @Test void propagatesSaveVetoWithoutChangingSelection() throws Exception {
        try (var server = new ProjectSelectionServer(new SessionAuthenticator("secret"), hello -> {
            throw new IOException("An editor could not be saved");
        }, () -> false)) {
            var failure = assertThrows(IOException.class, () -> ProjectSelectionRequest.send(server.port(), hello("secret", "b")));
            assertTrue(failure.getMessage().contains("could not be saved"));
        }
    }

    private static ClientHelloMessage hello(String token, String project) {
        return new ClientHelloMessage(CompanionProtocol.VERSION, token, project, "data", "game");
    }
}
