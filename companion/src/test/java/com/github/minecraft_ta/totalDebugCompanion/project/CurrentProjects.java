package com.github.minecraft_ta.totalDebugCompanion.project;

/** A current project for a test that builds what follows it, as the Project tree, without the application. */
public final class CurrentProjects {
    private CurrentProjects() {
    }

    /** A current project that is {@code scope}, or none where it is null. */
    public static CurrentProject of(ProjectScope scope) {
        CurrentProject current = new CurrentProject();
        current.set(scope);
        return current;
    }
}
