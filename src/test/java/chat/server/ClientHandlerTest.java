package chat.server;

import chat.protocol.MessageParser;
import chat.protocol.MessageType;
import chat.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ClientHandlerTest {
    @Test
    void closeInterruptsBlockedWriteWithoutWaitingForSendLock() throws Exception {
        BlockingSocket socket = new BlockingSocket();
        AtomicInteger cleanupCount = new AtomicInteger();
        ClientHandler handler = new ClientHandler(socket, new ChatService(), ignored -> cleanupCount.incrementAndGet());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var sending = executor.submit(() -> assertThrows(IOException.class, () -> handler.send(message("alice", "Hej"))));
            try {
                assertTrue(socket.writeStarted.await(2, TimeUnit.SECONDS));
                var closing = executor.submit(handler::close);
                closing.get(1, TimeUnit.SECONDS);
                sending.get(1, TimeUnit.SECONDS);
                handler.close();
                assertEquals(1, cleanupCount.get());
            } finally {
                // Også en fejlet test skal frigive den blokerede skriver.
                socket.close();
            }
        }
    }

    @Test
    void concurrentSendersProduceWholeParseableLines() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Socket socket = new MemorySocket(bytes);
        try (ClientHandler handler = new ClientHandler(socket, new ChatService(), ignored -> { });
             var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { sendMany(handler, "alice", start); return null; });
            var second = executor.submit(() -> { sendMany(handler, "bob", start); return null; });
            start.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            List<ServerMessage> messages = bytes.toString(java.nio.charset.StandardCharsets.UTF_8)
                    .lines().map(MessageParser::parseServer).toList();
            assertEquals(100, messages.size());
            for (String sender : List.of("alice", "bob")) {
                assertEquals(50, messages.stream().filter(item -> item.sender().equals(sender)).count());
                assertEquals(50, messages.stream().filter(item -> item.sender().equals(sender))
                        .map(ServerMessage::payload).distinct().count());
            }
        }
    }

    private void sendMany(ClientHandler handler, String sender, CountDownLatch start) throws Exception {
        assertTrue(start.await(2, TimeUnit.SECONDS));
        for (int i = 0; i < 50; i++) handler.send(message(sender, i + " | " + "æøå".repeat(200)));
    }

    private ServerMessage message(String sender, String payload) {
        return new ServerMessage(LocalDateTime.now(), MessageType.TEXT, sender, "lobby", payload);
    }

    private static class MemorySocket extends Socket {
        private final OutputStream output;

        MemorySocket(OutputStream output) { this.output = output; }
        public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        public OutputStream getOutputStream() { return output; }
    }

    private static final class BlockingSocket extends Socket {
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch socketClosed = new CountDownLatch(1);

        public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }

        public OutputStream getOutputStream() {
            return new OutputStream() {
                public void write(int value) throws IOException {
                    writeStarted.countDown();
                    try {
                        if (!socketClosed.await(3, TimeUnit.SECONDS)) throw new IOException("Skrivning blev ikke afbrudt.");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException(exception);
                    }
                    throw new SocketException("Socket lukket under skrivning.");
                }
            };
        }

        public void close() { socketClosed.countDown(); }
    }
}
