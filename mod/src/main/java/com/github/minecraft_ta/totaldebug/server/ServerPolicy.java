package com.github.minecraft_ta.totaldebug.server;

import com.github.minecraft_ta.totaldebug.config.TotalDebugConfig;

/**
 * Whether the server does for a player what Companion asks it to, as its configuration sets it (see
 * {@code docs/MOD_SIDES.md}): running scripts, and changing its world, each with a switch and whether it needs operator
 * permission. {@code disabled} and {@code notPermitted} say why not.
 */
public record ServerPolicy(boolean enabled, boolean operatorOnly, String disabled, String notPermitted) {
    /** Running scripts, as the server's configuration sets it. */
    public static ServerPolicy scripts() {
        return new ServerPolicy(TotalDebugConfig.SERVER.enableScripts.get(), TotalDebugConfig.SERVER.enableScriptsOnlyForOp.get(),
                "Server-side scripts are disabled by the server configuration", "You do not have permission to run server-side scripts");
    }

    /**
     * Changing the world, such as its datapacks, as the server's configuration sets it; by default open to its operators.
     * Operator permission is the level {@code /datapack} and {@code /reload} require, which the caller checks.
     */
    public static ServerPolicy worldChanges() {
        return new ServerPolicy(TotalDebugConfig.SERVER.enableWorldChanges.get(), TotalDebugConfig.SERVER.enableWorldChangesOnlyForOp.get(),
                "Changing this server's world from Companion is disabled by the server configuration",
                "You need operator permission on this server to change its world");
    }

    public Decision evaluate(boolean hasOperatorPermission) {
        if (!this.enabled) return Decision.rejected(this.disabled);
        if (this.operatorOnly && !hasOperatorPermission) return Decision.rejected(this.notPermitted);
        return Decision.accepted();
    }

    public record Decision(boolean allowed, String rejectionReason) {
        public static Decision accepted() {
            return new Decision(true, "");
        }

        private static Decision rejected(String reason) {
            return new Decision(false, reason);
        }
    }
}
