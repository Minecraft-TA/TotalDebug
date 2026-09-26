package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import java.util.Objects;

/**
 * The target a script run is bound to: a subject in the game session where it was selected. When
 * {@code expectedId} is not empty the run only starts while the subject still has that registry id.
 */
public record ScriptSubject(SubjectRef subject, String gameSessionId, String expectedId) {
    public ScriptSubject {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(gameSessionId, "gameSessionId");
        if (gameSessionId.isBlank()) {
            throw new IllegalArgumentException("A script subject requires its game session");
        }
        expectedId = Objects.requireNonNullElse(expectedId, "");
    }

    /** A subject accepting whatever occupies it. */
    public ScriptSubject(SubjectRef subject, String gameSessionId) {
        this(subject, gameSessionId, "");
    }
}
