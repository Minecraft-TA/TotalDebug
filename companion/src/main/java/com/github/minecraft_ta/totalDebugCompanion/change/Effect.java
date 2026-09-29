package com.github.minecraft_ta.totalDebugCompanion.change;

/**
 * When a change takes effect in the game (see {@code docs/CHANGE_PIPELINE.md}): at once, after a rejoin or a restart,
 * when the game starts or the world opens, or for worlds created later.
 */
public enum Effect {
    NOW("the game reloaded it"),
    REJOIN("takes effect after rejoining the world"),
    RESTART("takes effect after restarting the game"),
    GAME_STARTS("applies when the game starts"),
    WORLD_OPENS("applies when the world opens"),
    NEW_WORLDS("applies to worlds created from now on");

    private final String description;

    Effect(String description) {
        this.description = description;
    }

    public String description() {
        return this.description;
    }

    /** Whether the running game keeps using the previous value until something happens. */
    public boolean pending() {
        return this == REJOIN || this == RESTART;
    }
}
