package com.github.minecraft_ta.totalDebugCompanion.project;

import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ProjectScopeTest {
    private ProjectScope scope() {
        return new ProjectScope(new Object(), new CompanionProfile("test", Path.of("data"), Path.of("game")), InstanceState.inMemory());
    }

    @Test void vetoReopensAdmissionButRetirementIsTerminal() throws Exception {
        var scope = scope();
        assertEquals("accepted", scope.admit(() -> "accepted"));
        assertThrows(IllegalStateException.class, scope::close);
        scope.beginSwitch();
        assertFalse(scope.isActive());
        assertThrows(IllegalStateException.class, () -> scope.admit(() -> true));
        scope.cancelSwitch();
        assertTrue(scope.admit(() -> true));
        scope.retire();
        scope.cancelSwitch();
        assertEquals(ProjectScope.Phase.RETIRED, scope.phase());
        assertThrows(IllegalStateException.class, () -> scope.admit(() -> true));
        scope.close();
        scope.close();
    }

    @Test void switchCannotPassAnAdmittedSubmission() throws Exception {
        var scope = scope();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var submitted = new CompletableFuture<Void>();
        var admission = CompletableFuture.runAsync(() -> scope.admit(() -> {
            entered.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
            submitted.complete(null);
            return null;
        }));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var switchStarted = new CountDownLatch(1);
        var switching = CompletableFuture.runAsync(() -> {
            switchStarted.countDown();
            scope.beginSwitch();
            assertTrue(submitted.isDone(), "The admitted job must reach its queue before switching");
        });
        assertTrue(switchStarted.await(3, TimeUnit.SECONDS));
        release.countDown();
        admission.get(3, TimeUnit.SECONDS);
        switching.get(3, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, () -> scope.admit(() -> true));
        scope.retire();
        scope.close();
    }
}
