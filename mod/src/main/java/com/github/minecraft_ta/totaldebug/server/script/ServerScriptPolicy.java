package com.github.minecraft_ta.totaldebug.server.script;

record ServerScriptPolicy(boolean enabled, boolean operatorOnly) {
    Decision evaluate(boolean hasOperatorPermission) {
        if (!this.enabled) {
            return Decision.rejected("Server-side scripts are disabled by the server configuration");
        }
        if (this.operatorOnly && !hasOperatorPermission) {
            return Decision.rejected("You do not have permission to run server-side scripts");
        }
        return Decision.accepted();
    }

    record Decision(boolean allowed, String rejectionReason) {
        private static Decision accepted() {
            return new Decision(true, "");
        }

        private static Decision rejected(String reason) {
            return new Decision(false, reason);
        }
    }
}
