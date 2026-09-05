import com.github.tth05.scnet.Client;
import com.github.tth05.scnet.IConnectionListener;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import com.github.tth05.scnet.message.impl.EmptyMessage;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Read-only audit reproduction. Does not edit transport sources. */
class TransportAuditProbe {
    public static void main(String[] args) throws Exception {
        messageBusMutation();
        reconnectDuringCallbackClose();
    }

    private static void messageBusMutation() {
        var bus = new DefaultMessageBus();
        bus.listenAlways(EmptyMessage.class,
                message -> bus.listenAlways(EmptyMessage.class, next -> {}));
        try {
            bus.post(new EmptyMessage());
            throw new AssertionError("Expected callback mutation to reproduce CME");
        } catch (ConcurrentModificationException expected) {
            System.out.println("CONFIRMED: callback registration escapes post as " + expected);
        }
    }

    private static void reconnectDuringCallbackClose() throws Exception {
        try (var raw = ServerSocketChannel.open()) {
            raw.bind(new InetSocketAddress("127.0.0.1", 0));
            var address = raw.getLocalAddress();
            var client = new Client();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var closeReturned = new CountDownLatch(1);
            client.addConnectionListener(new IConnectionListener() {
                @Override public void onDisconnected() {}
                @Override public void onConnected() {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    client.close();
                    closeReturned.countDown();
                }
            });
            if (!client.connect(address) || !entered.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("Connection callback did not start");
            }
            var reconnect = new Thread(() -> client.connect(address), "audit-reconnect");
            reconnect.setDaemon(true);
            reconnect.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (Arrays.stream(reconnect.getStackTrace()).noneMatch(frame ->
                    frame.getMethodName().equals("closeAndAwaitEventLoop"))) {
                if (System.nanoTime() > deadline) throw new AssertionError("Reconnect did not reach transport wait");
                Thread.sleep(1);
            }
            release.countDown();
            if (closeReturned.await(250, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Expected callback close to block on reconnect monitor");
            }
            var traces = Thread.getAllStackTraces();
            boolean callbackBlocked = traces.keySet().stream().anyMatch(thread ->
                    thread.getName().equals("SCNet Client") && thread.getState() == Thread.State.BLOCKED);
            if (!callbackBlocked || reconnect.getState() != Thread.State.WAITING) {
                throw new AssertionError("Expected BLOCKED callback and WAITING reconnect");
            }
            System.out.println("CONFIRMED: reconnect monitor / transport callback deadlock");
            traces.forEach((thread, stack) -> {
                if (thread.getName().equals("audit-reconnect") || thread.getName().equals("SCNet Client")) {
                    System.out.println(thread.getName() + " " + thread.getState());
                    Arrays.stream(stack).limit(9).forEach(frame -> System.out.println("  " + frame));
                }
            });
            // Both blocked threads are daemon threads. Process exit releases the probe connection.
        }
    }
}
