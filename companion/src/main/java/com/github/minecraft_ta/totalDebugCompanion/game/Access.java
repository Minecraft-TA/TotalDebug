package com.github.minecraft_ta.totalDebugCompanion.game;

import java.util.Objects;

/** How a change can be made now, as {@link GameState} answers for what it belongs to. */
public sealed interface Access {
    /** The connected game applies it. */
    record Live(GameLocation.Connection connection) implements Access {
        public Live {
            Objects.requireNonNull(connection, "connection");
        }
    }

    /** Companion writes the files; the game uses them when it next reads them. */
    record Files() implements Access {
    }

    /** Neither is possible; {@code reason} says why and what to do. */
    record Refused(String reason) implements Access {
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
