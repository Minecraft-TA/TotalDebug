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
        GameRulesPayload current = new GameRulesPayload(rules(Minecraft.getInstance().getSingleplayerServer()));
        synchronized (this) {
            if (current.equals(this.published)) return;
            this.published = current;
        }
        this.publish.accept(current);
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
     * command does, then answers with what the command reported when it failed. Client thread only.
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
