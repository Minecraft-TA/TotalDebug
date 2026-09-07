package com.github.minecraft_ta.totaldebug.network;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Lifecycle boundary between Minecraft networking and the client companion connection.
 */
public final class ForwardedCompanionPayloadSink {
    private final AtomicReference<Consumer<ForwardedCompanionPayload>> receiver = new AtomicReference<>();

    public AutoCloseable install(Consumer<ForwardedCompanionPayload> newReceiver) {
        Objects.requireNonNull(newReceiver, "newReceiver");
        if (!this.receiver.compareAndSet(null, newReceiver)) {
            throw new IllegalStateException("A forwarded companion payload receiver is already installed");
        }

        return new Registration(this.receiver, newReceiver);
    }

    public boolean deliver(ForwardedCompanionPayload payload) {
        Objects.requireNonNull(payload, "payload");
        Consumer<ForwardedCompanionPayload> currentReceiver = this.receiver.get();
        if (currentReceiver == null) {
            return false;
        }

        currentReceiver.accept(payload);
        return true;
    }

    private static final class Registration implements AutoCloseable {
        private final AtomicReference<Consumer<ForwardedCompanionPayload>> receiver;
        private final Consumer<ForwardedCompanionPayload> installedReceiver;

        private Registration(
                AtomicReference<Consumer<ForwardedCompanionPayload>> receiver,
                Consumer<ForwardedCompanionPayload> installedReceiver
        ) {
            this.receiver = receiver;
            this.installedReceiver = installedReceiver;
        }

        @Override
        public void close() {
            this.receiver.compareAndSet(this.installedReceiver, null);
        }
    }
}
