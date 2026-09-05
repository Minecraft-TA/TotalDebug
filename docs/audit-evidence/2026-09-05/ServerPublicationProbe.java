import com.github.tth05.scnet.IConnectionListener;
import com.github.tth05.scnet.Server;
import com.github.tth05.scnet.message.IMessageBus;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Scheduling stress probe. No production instrumentation. */
class ServerPublicationProbe {
    public static void main(String[] args) throws Exception {
        var clientField = Server.class.getDeclaredField("client");
        clientField.setAccessible(true);
        for (int attempt = 1; attempt <= 5000; attempt++) {
            try (var server = new Server()) {
                var closed = new CountDownLatch(1);
                server.setMessageProcessor(new DefaultMessageProcessor() {
                    @Override public boolean process(Selector selector, SocketChannel channel, IMessageBus bus) {
                        return false;
                    }
                });
                server.addConnectionListener(new IConnectionListener() {
                    @Override public void onConnected() {}
                    @Override public void onDisconnected() { closed.countDown(); }
                });
                server.bind(new InetSocketAddress("127.0.0.1", 0));
                try (var raw = SocketChannel.open(server.getLocalAddress())) {
                    if (!closed.await(3, TimeUnit.SECONDS)) throw new AssertionError("No disconnect callback");
                    // The callback occurs after Server.onClientClosed. Give a preempted accept thread
                    // time to finish the constructor and publish its client.
                    Thread.sleep(1);
                    Object retained = clientField.get(server);
                    if (retained != null) {
                        System.out.println("CONFIRMED: closed client retained after disconnect on attempt " + attempt);
                        System.out.println("server.isClientConnected=" + server.isClientConnected());
                        System.out.println("server.client=" + retained);
                        return;
                    }
                }
            }
        }
        System.out.println("NOT_REPRODUCED: 5000 scheduling attempts; static race remains");
    }
}
