package com.github.minecraft_ta.totaldebug.config;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class TotalDebugConfig {
    public static final Client CLIENT;
    public static final ModConfigSpec CLIENT_SPEC;
    public static final Server SERVER;
    public static final ModConfigSpec SERVER_SPEC;

    private static final Pattern BINARY_CLASS_NAME = Pattern.compile(
            "(?:[\\p{L}_$][\\p{L}\\p{N}_$]*\\.)*[\\p{L}_$][\\p{L}\\p{N}_$]*"
    );

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

    public static List<String> blockedPacketClasses() {
        return List.copyOf(CLIENT.blockedPacketClasses.get());
    }

    public static void setBlockedPacketClasses(Collection<String> classNames) {
        Objects.requireNonNull(classNames, "classNames");
        List<String> normalized = List.copyOf(new LinkedHashSet<>(classNames));
        if (!normalized.stream().allMatch(TotalDebugConfig::isValidPacketClassName)) {
            throw new IllegalArgumentException("Every blocked packet entry must be a Java binary class name");
        }

        CLIENT.blockedPacketClasses.set(normalized);
        CLIENT.blockedPacketClasses.save();
    }

    static boolean isValidPacketClassName(Object value) {
        return value instanceof String className && BINARY_CLASS_NAME.matcher(className).matches();
    }

    public static final class Client {
        public final ModConfigSpec.BooleanValue useCompanionApp;
        public final ModConfigSpec.ConfigValue<List<? extends String>> blockedPacketClasses;

        private Client(ModConfigSpec.Builder builder) {
            builder.push("decompilation");
            this.useCompanionApp = builder
                    .comment("Enable TotalDebug Companion for browsing and decompiling runtime classes.")
                    .define("useCompanionApp", true);
            builder.pop();

            builder.push("network");
            this.blockedPacketClasses = builder
                    .comment("Java binary class names of packets the server should not send to this client.")
                    .defineListAllowEmpty(
                            "blockedPacketClasses",
                            List.of(),
                            () -> "net.minecraft.network.protocol.PacketClass",
                            TotalDebugConfig::isValidPacketClassName
                    );
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
