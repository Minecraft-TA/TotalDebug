package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import java.util.Objects;

/**
 * The target a script run is bound to: a subject in the world where it was selected. When
 * {@code expectedId} is not empty the run only starts while the subject still has that registry id.
 */
public record ScriptSubject(SubjectRef.Occurrence subject, String world, String expectedId) {
    public ScriptSubject {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(world, "world");
        if (world.isBlank()) {
            throw new IllegalArgumentException("A script subject requires its world");
        }
        expectedId = Objects.requireNonNullElse(expectedId, "");
    }

    /** A subject accepting whatever occupies it. */
    public ScriptSubject(SubjectRef.Occurrence subject, String world) {
        this(subject, world, "");
    }
}
