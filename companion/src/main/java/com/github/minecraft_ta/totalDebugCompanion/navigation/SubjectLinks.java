package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;

/** Where a fact link leads in Companion. */
public final class SubjectLinks {
    private SubjectLinks() {
    }

    public static NavigationTarget target(FactLink link) {
        return switch (link.kind()) {
            case CLASS -> new NavigationTarget.RuntimeClass(link.target());
            case SUBJECT -> target(SubjectRef.parse(link.target()));
        };
    }

    public static NavigationTarget target(SubjectRef subject) {
        return switch (subject) {
            case SubjectRef.Mod mod -> new NavigationTarget.ModPage(mod.modId());
            case SubjectRef.Definition definition -> new NavigationTarget.Definition(definition);
            case SubjectRef.Occurrence occurrence -> throw new IllegalArgumentException("Opening " + occurrence.format()
                    + " from a link requires its game session; select it in the game with F6");
        };
    }
}
