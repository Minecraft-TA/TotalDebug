package com.github.minecraft_ta.totaldebug.client.world;

import com.github.minecraft_ta.totaldebug.protocol.message.GameRulesPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetGameRulePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The game rules of the singleplayer world the game has open: told to Companion whenever they change or Companion
 * connects again, checked once a second on the client thread, and set as {@code /gamerule} sets them.
 */
public final class GameRuleControl {
    private static final int CHECK_TICKS = 20;
    private static final String NAME = "[A-Za-z0-9_.-]+";

    private final Consumer<GameRulesPayload> publish;
    private GameRulesPayload published;
    private int ticks;

    public GameRuleControl(Consumer<GameRulesPayload> publish) {
        this.publish = Objects.requireNonNull(publish, "publish");
    }

    /** Publishes the rules again at the next check, such as for a newly connected Companion. */
    public synchronized void republish() {
        this.published = null;
        this.ticks = CHECK_TICKS;
    }

    /** Client thread only. */
    public void tick() {
        synchronized (this) {
            if (++this.ticks < CHECK_TICKS) return;
            this.ticks = 0;
        }
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        GameRulesPayload current = new GameRulesPayload(server == null ? "" : world(server), rules(server));
        synchronized (this) {
            if (current.equals(this.published)) return;
            this.published = current;
        }
        this.publish.accept(current);
    }

    /** The folder name of {@code server}'s world, which Companion names the world by. */
    private static String world(IntegratedServer server) {
        Path folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
        return folder == null ? "" : folder.toString();
    }

    /** The rules of {@code server}'s world by name, as {@code /gamerule} prints them; none without a server. */
    private static Map<String, String> rules(IntegratedServer server) {
        Map<String, String> rules = new TreeMap<>();
        if (server == null) return rules;
        GameRules gameRules = server.getGameRules();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                rules.put(key.getId(), gameRules.getRule(key).serialize());
            }
        });
        return rules;
    }

    /**
     * Sets the rule {@code request} names in the open world by running {@code /gamerule}, which checks the value as the
     * command does, then answers with what the command reported when it failed. A request for another world, or for a
     * rule that no longer has the value it expects, is refused. Client thread only.
     */
    public static void set(SetGameRulePayload request, Consumer<ReloadResultPayload> answer) {
        long started = System.nanoTime();
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            answer.accept(new ReloadResultPayload(request.requestId(), 0, List.of(), "No singleplayer world is open"));
            return;
        }
        if (!request.name().matches(NAME) || request.value().isBlank() || request.value().contains("\n")) {
            answer.accept(new ReloadResultPayload(request.requestId(), 0, List.of(), "Not a game rule and value: "
                    + request.name() + " " + request.value()));
            return;
        }
        server.execute(() -> {
            // The world and the value the request was made for, checked where the command runs, so nothing changes between.
            String folder = world(server);
            if (!folder.equals(request.world())) {
                answer.accept(new ReloadResultPayload(request.requestId(), 0, List.of(), "The game has " + folder + " open, not " + request.world()));
                return;
            }
            String now = rules(server).get(request.name());
            if (now == null) {
                answer.accept(new ReloadResultPayload(request.requestId(), 0, List.of(), "The world has no game rule " + request.name()));
                return;
            }
            if (!now.equals(request.expected())) {
                answer.accept(new ReloadResultPayload(request.requestId(), 0, List.of(),
                        request.name() + " is " + now + " now; it was changed since"));
                return;
            }
            List<String> failures = new ArrayList<>();
            boolean[] succeeded = new boolean[1];
            CommandSource capture = new CommandSource() {
                @Override
                public void sendSystemMessage(Component message) {
                    failures.add(message.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return false;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };
            CommandSourceStack source = server.createCommandSourceStack().withSource(capture).withPermission(4)
                    .withCallback((success, result) -> succeeded[0] = success);
            server.getCommands().performPrefixedCommand(source, "gamerule " + request.name() + " " + request.value());
            String error = succeeded[0] ? "" : failures.isEmpty() ? "The game did not set " + request.name() : String.join("; ", failures);
            answer.accept(new ReloadResultPayload(request.requestId(), (System.nanoTime() - started) / 1_000_000, List.of(), error));
        });
    }
}
