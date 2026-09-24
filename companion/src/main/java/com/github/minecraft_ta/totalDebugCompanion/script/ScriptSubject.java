package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import java.util.Objects;

/** The target a script run is bound to: a subject in the game session where it was selected. */
public record ScriptSubject(SubjectRef subject, String gameSessionId) {
    public ScriptSubject {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(gameSessionId, "gameSessionId");
        if (gameSessionId.isBlank()) {
            throw new IllegalArgumentException("A script subject requires its game session");
        }
    }
}
