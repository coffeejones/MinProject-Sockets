package chat.client;

import chat.protocol.ClientMessage;
import chat.protocol.MessageParser;
import chat.protocol.MessageType;
import chat.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Kontrollerer rigtig socket-I/O uden at være afhængig af chatserverens logik. */
@Timeout(8)
class ChatConnectionTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 10, 15, 0);

    @Test
    void sendsUtf8AndDeliversBroadcastBeforeLoginAcknowledgement() throws Exception {
        Events events = new Events();
        try (ServerSocket server = server(); ChatConnection client = new ChatConnection(events)) {
            CompletableFuture<Void> connecting = client.connect("127.0.0.1", server.getLocalPort());
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(2_000);
                connecting.get(2, TimeUnit.SECONDS);
                assertEquals(1, events.connected.get());
                client.send(new ClientMessage(MessageType.TEXT, "lobby", "Hej æøå | verden"))
                        .get(2, TimeUnit.SECONDS);
                assertEquals("TEXT|lobby|Hej æøå | verden", reader(peer).readLine());

                ServerMessage early = new ServerMessage(NOW, MessageType.TEXT, "alice", "lobby", "Velkommen!");
                ServerMessage login = new ServerMessage(NOW, MessageType.LOGIN_OK, "server", "lobby", "bob");
                BufferedWriter output = writer(peer);
                send(output, early);
                send(output, login);
                assertEquals(early, events.messages.poll(2, TimeUnit.SECONDS));
                assertEquals(login, events.messages.poll(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void reportsUnexpectedServerEofAndCompletes() throws Exception {
        Events events = new Events();
        try (ServerSocket server = server(); ChatConnection client = new ChatConnection(events)) {
            CompletableFuture<Void> connecting = client.connect("127.0.0.1", server.getLocalPort());
            Socket peer = server.accept();
            connecting.get(2, TimeUnit.SECONDS);
            peer.close();
            client.completion().get(2, TimeUnit.SECONDS);
            assertTrue(client.isClosed());
            assertTrue(events.disconnected.poll(2, TimeUnit.SECONDS));
            assertEquals(1, events.disconnectCount.get());
            assertThrows(ExecutionException.class, () -> client.send(
                    new ClientMessage(MessageType.TEXT, "lobby", "Hej")).get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void localCloseIsIdempotentAndClosesPeerSocket() throws Exception {
        Events events = new Events();
        try (ServerSocket server = server(); ChatConnection client = new ChatConnection(events)) {
            CompletableFuture<Void> connecting = client.connect("127.0.0.1", server.getLocalPort());
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(2_000);
                connecting.get(2, TimeUnit.SECONDS);
                client.close();
                client.close();
                client.quit();
                client.completion().get(2, TimeUnit.SECONDS);
                assertNull(reader(peer).readLine());
                assertEquals(1, events.disconnectCount.get());
                assertFalse(events.disconnected.poll(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void quitWaitsForByeAndMarksClosureAsExpected() throws Exception {
        Events events = new Events();
        try (ServerSocket server = server(); ChatConnection client = new ChatConnection(events)) {
            CompletableFuture<Void> connecting = client.connect("127.0.0.1", server.getLocalPort());
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(2_000);
                connecting.get(2, TimeUnit.SECONDS);
                client.quit();
                assertEquals("QUIT||", reader(peer).readLine());
                assertFalse(client.isClosed());
                ServerMessage bye = new ServerMessage(NOW, MessageType.BYE, "server", "", "Farvel.");
                send(writer(peer), bye);
                client.completion().get(2, TimeUnit.SECONDS);
                assertEquals(bye, events.messages.poll(2, TimeUnit.SECONDS));
                assertFalse(events.disconnected.poll(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void quitClosesAfterDeadlineWhenServerDoesNotReply() throws Exception {
        Events events = new Events();
        try (ServerSocket server = server(); ChatConnection client = new ChatConnection(events)) {
            CompletableFuture<Void> connecting = client.connect("127.0.0.1", server.getLocalPort());
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(3_500);
                connecting.get(2, TimeUnit.SECONDS);
                client.quit();
                BufferedReader input = reader(peer);
                assertEquals("QUIT||", input.readLine());
                client.completion().get(4, TimeUnit.SECONDS);
                assertNull(input.readLine());
                assertFalse(events.disconnected.poll(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void invalidOutgoingMessageDoesNotCorruptConnection() throws Exception {
        Events events = new Events();
        try (ServerSocket server = server(); ChatConnection client = new ChatConnection(events)) {
            CompletableFuture<Void> connecting = client.connect("127.0.0.1", server.getLocalPort());
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(2_000);
                connecting.get(2, TimeUnit.SECONDS);
                assertThrows(ExecutionException.class, () -> client.send(
                        new ClientMessage(MessageType.TEXT, "lobby", "Hej\nQUIT||"))
                        .get(2, TimeUnit.SECONDS));
                assertNotNull(events.errors.poll(2, TimeUnit.SECONDS));
                assertFalse(client.isClosed());
                client.send(new ClientMessage(MessageType.TEXT, "lobby", "Gyldig besked"))
                        .get(2, TimeUnit.SECONDS);
                assertEquals("TEXT|lobby|Gyldig besked", reader(peer).readLine());
            }
        }
    }

    @Test
    void closeDuringConnectionCallbackCompletesQueuedSends() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Events events = new Events() {
            @Override
            public void onConnected() {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try (ServerSocket server = server(); ChatConnection client = new ChatConnection(events)) {
            CompletableFuture<Void> connecting = client.connect("127.0.0.1", server.getLocalPort());
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(2_000);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                List<CompletableFuture<Void>> queued = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    queued.add(client.send(new ClientMessage(MessageType.TEXT, "lobby", "Besked " + i)));
                }
                client.close();
                client.completion().get(2, TimeUnit.SECONDS);
                for (CompletableFuture<Void> future : queued) {
                    assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
                }
                assertTrue(connecting.isDone());
                assertNull(reader(peer).readLine());
                assertEquals(1, events.disconnectCount.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void immediateCloseRacingWithConnectAlwaysCompletes() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            Events events = new Events();
            try (ServerSocket server = server(); ChatConnection client = new ChatConnection(events)) {
                CompletableFuture<Void> connecting = client.connect("127.0.0.1", server.getLocalPort());
                client.close();
                client.completion().get(2, TimeUnit.SECONDS);
                assertTrue(client.isClosed());
                assertTrue(connecting.isDone());
                assertEquals(1, events.disconnectCount.get());
            }
        }
    }

    @Test
    void connectionFailureReportsErrorBeforeCompletingFuture() throws Exception {
        int closedPort;
        try (ServerSocket reservation = server()) {
            closedPort = reservation.getLocalPort();
        }
        Events events = new Events();
        try (ChatConnection client = new ChatConnection(events)) {
            assertThrows(ExecutionException.class,
                    () -> client.connect("127.0.0.1", closedPort).get(4, TimeUnit.SECONDS));
            assertNotNull(events.errors.poll());
            client.completion().get(2, TimeUnit.SECONDS);
            assertEquals(1, events.disconnectCount.get());
        }
    }

    private static ServerSocket server() throws Exception {
        ServerSocket server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(2_000);
        return server;
    }

    private static BufferedReader reader(Socket socket) throws Exception {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private static BufferedWriter writer(Socket socket) throws Exception {
        return new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    private static void send(BufferedWriter writer, ServerMessage message) throws Exception {
        writer.write(MessageParser.format(message));
        writer.newLine();
        writer.flush();
    }

    private static class Events implements ConnectionListener {
        final BlockingQueue<ServerMessage> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        final BlockingQueue<Boolean> disconnected = new LinkedBlockingQueue<>();
        final AtomicInteger connected = new AtomicInteger();
        final AtomicInteger disconnectCount = new AtomicInteger();

        @Override
        public void onConnected() {
            connected.incrementAndGet();
        }

        @Override
        public void onMessage(ServerMessage message) {
            messages.add(message);
        }

        @Override
        public void onError(String message) {
            errors.add(message);
        }

        @Override
        public void onDisconnected(String reason, boolean unexpected) {
            disconnectCount.incrementAndGet();
            disconnected.add(unexpected);
        }
    }
}
