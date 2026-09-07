package com.github.minecraft_ta.totaldebug.config;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Objects;

public final class TotalDebugConfig {
    public static final Client CLIENT;
    public static final ModConfigSpec CLIENT_SPEC;
    public static final Server SERVER;
    public static final ModConfigSpec SERVER_SPEC;

    static {
        Pair<Client, ModConfigSpec> client = new ModConfigSpec.Builder().configure(Client::new);
        CLIENT = client.getLeft();
        CLIENT_SPEC = client.getRight();

        Pair<Server, ModConfigSpec> server = new ModConfigSpec.Builder().configure(Server::new);
        SERVER = server.getLeft();
        SERVER_SPEC = server.getRight();
    }

    private TotalDebugConfig() {
    }

    public static void register(ModContainer modContainer) {
        Objects.requireNonNull(modContainer, "modContainer");
        modContainer.registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC, TotalDebug.MOD_ID + "-client.toml");
        modContainer.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC, TotalDebug.MOD_ID + "-server.toml");
    }

    public static final class Client {
        public final ModConfigSpec.BooleanValue useCompanionApp;
        public final ModConfigSpec.ConfigValue<String> companionDevelopmentJar;

        private Client(ModConfigSpec.Builder builder) {
            builder.push("decompilation");
            this.useCompanionApp = builder
                    .comment("Enable TotalDebug Companion for browsing and decompiling runtime classes.")
                    .define("useCompanionApp", true);
            this.companionDevelopmentJar = builder
                    .comment(
                            "Optional path to a mutable TotalDebugCompanion JAR for development.",
                            "Rebuild the JAR, close Companion, then press F6 to launch the new build."
                    )
                    .define("companionDevelopmentJar", "");
            builder.pop();

        }
    }

    public static final class Server {
        public final ModConfigSpec.BooleanValue enableScripts;
        public final ModConfigSpec.BooleanValue enableScriptsOnlyForOp;

        private Server(ModConfigSpec.Builder builder) {
            builder.push("scripts");
            this.enableScripts = builder
                    .comment("Allow TotalDebug Java scripts to execute on the logical server.")
                    .define("enableScripts", false);
            this.enableScriptsOnlyForOp = builder
                    .comment("Require server operator permission for players that execute TotalDebug scripts.")
                    .define("enableScriptsOnlyForOp", true);
            builder.pop();
        }
    }
}
