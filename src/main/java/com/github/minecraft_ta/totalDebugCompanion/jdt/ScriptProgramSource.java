package com.github.minecraft_ta.totalDebugCompanion.jdt;

/** Source mirror used by JDT before the connected runtime index exposes ScriptProgram. */
public final class ScriptProgramSource {
    private static final String SOURCE = """
            package com.github.minecraft_ta.totaldebug.script;

            import net.minecraft.server.MinecraftServer;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import java.util.List;

            public abstract class ScriptProgram {
                public final MinecraftServer getServer() { return null; }
                public final void sendToAllPlayers(String message) { }
                public final ServerLevel getServerOverworld() { return null; }
                public final List<ServerLevel> getServerWorlds() { return null; }
                public final List<ServerPlayer> getServerPlayers() { return null; }
                public final void logln(Object value) { }
                public final void log(Object value) { }
                protected final Object noResult() { return null; }
                public abstract Object run() throws Throwable;
            }
            """;

    private ScriptProgramSource() {
    }

    public static String text() {
        return SOURCE;
    }
}
