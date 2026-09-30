package com.github.minecraft_ta.totalDebugCompanion.project;

import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The project the window shows, whose changes reach the window only while it is the current one. */
class CurrentProjectTest {
    @TempDir Path directory;

    @Test
    void aFollowerHearsOnlyTheCurrentProjectAndMovesWithASwitch() throws Exception {
        try (ProjectScope first = scope("first"); ProjectScope second = scope("second")) {
            CurrentProject current = new CurrentProject();
            current.set(first);
            AtomicInteger told = new AtomicInteger();
            Runnable stop = current.follows(scope -> scope.changes().changed(), told::incrementAndGet);

            fire(first);
            assertEquals(1, told.get(), "the current project's change is told");

            current.set(second);
            fire(first);
            assertEquals(1, told.get(), "a project no longer current tells nothing");
            fire(second);
            assertEquals(2, told.get(), "the follower moved to the project now current");

            // A change the previous project told just before the switch arrives after it.
            current.set(first);
            SwingUtilities.invokeAndWait(() -> {
                first.changes().changed().fire();
                current.set(second);
            });
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(2, told.get(), "a change of the project before, queued before the switch, is not told");

            stop.run();
            fire(second);
            assertEquals(2, told.get(), "a follower that stopped hears nothing");
            first.retire();
            second.retire();
        }
    }

    private ProjectScope scope(String name) throws Exception {
        Path game = Files.createDirectories(this.directory.resolve(name));
        return new ProjectScope(new Object(), new CompanionProfile(name, game, game), InstanceState.inMemory(), ChangeRecord.inMemory());
    }

    private static void fire(ProjectScope scope) throws Exception {
        scope.changes().changed().fire();
        SwingUtilities.invokeAndWait(() -> { });
    }
}
