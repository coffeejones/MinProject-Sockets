package chat.server;

import chat.protocol.MessageParser;
import chat.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static chat.protocol.MessageType.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ChatServerTest {
    @Test
    void malformedLineCanBeFollowedByValidCommandsOnSameConnection() throws Exception {
        try (ChatServer server = new ChatServer(0)) {
            server.start();
            try (WireClient client = new WireClient(server.port())) {
                client.send("TEXT|lobby|Jeg er ikke logget ind");
                assertEquals(ERROR, client.read().type());
                client.send("forkert-format");
                assertEquals(ERROR, client.read().type());
                client.send("LOGIN||Alice");
                ServerMessage login = client.read();
                assertEquals(LOGIN_OK, login.type());
                assertEquals("alice", login.payload());
                client.send("TEXT|lobby|Hej | æøå");
                ServerMessage echo = client.read();
                assertEquals(TEXT, echo.type());
                assertEquals("alice", echo.sender());
                assertEquals("Hej | æøå", echo.payload());
                client.send("QUIT||");
                assertEquals(BYE, client.read().type());
                assertNull(client.readLine());
            }
        }
    }

    @Test
    void capacityIncludesClientsBeforeLoginAndIsFreedAfterQuit() throws Exception {
        try (ChatServer server = new ChatServer(0, 1)) {
            server.start();
            try (WireClient anonymous = new WireClient(server.port())) {
                // Svaret bekræfter, at den første forbindelse er accepteret.
                anonymous.send("forkert-format");
                assertEquals(ERROR, anonymous.read().type());
                try (WireClient extra = new WireClient(server.port())) {
                    ServerMessage rejection = extra.read();
                    assertEquals(ERROR, rejection.type());
                    assertTrue(rejection.payload().contains("fuld"));
                    assertNull(extra.readLine());
                }
                anonymous.send("QUIT||");
                assertEquals(BYE, anonymous.read().type());
                assertNull(anonymous.readLine());
            }
            try (WireClient next = loginEventually(server.port(), "alice")) {
                next.send("TEXT|lobby|Pladsen er ledig igen");
                assertEquals(TEXT, next.read().type());
            }
        }
    }

    @Test
    void eofFreesUsernameAndCapacity() throws Exception {
        try (ChatServer server = new ChatServer(0, 1)) {
            server.start();
            try (WireClient first = new WireClient(server.port())) {
                first.send("LOGIN||alice");
                assertEquals(LOGIN_OK, first.read().type());
                first.socket.shutdownOutput();
                assertNull(first.readLine());
            }
            try (WireClient replacement = loginEventually(server.port(), "alice")) {
                replacement.send("TEXT|lobby|Jeg er tilbage");
                assertEquals(TEXT, replacement.read().type());
            }
        }
    }

    @Test
    void abruptSocketResetAlsoFreesUsername() throws Exception {
        try (ChatServer server = new ChatServer(0)) {
            server.start();
            try (WireClient first = new WireClient(server.port())) {
                first.send("LOGIN||alice");
                assertEquals(LOGIN_OK, first.read().type());
                first.socket.setSoLinger(true, 0);
            }
            try (WireClient replacement = loginEventually(server.port(), "alice")) {
                replacement.send("QUIT||");
                assertEquals(BYE, replacement.read().type());
            }
        }
    }

    @Test
    void serverStopClosesLoggedInAndAnonymousClients() throws Exception {
        try (ChatServer server = new ChatServer(0)) {
            server.start();
            int port = server.port();
            try (WireClient loggedIn = new WireClient(port); WireClient anonymous = new WireClient(port)) {
                loggedIn.send("LOGIN||alice");
                assertEquals(LOGIN_OK, loggedIn.read().type());
                anonymous.send("forkert-format");
                assertEquals(ERROR, anonymous.read().type());
                server.close();
                server.close();
                assertNull(loggedIn.readLine());
                assertNull(anonymous.readLine());
                try (Socket another = new Socket()) {
                    assertThrows(IOException.class, () -> another.connect(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500));
                }
            }
        }
    }

    @Test
    void serverCanBeClosedBeforeItStarts() throws Exception {
        ChatServer server = new ChatServer(0);
        server.close();
        server.close();
        assertThrows(IllegalStateException.class, server::start);
    }

    private WireClient loginEventually(int port, String name) throws Exception {
        // EOF/RST registreres asynkront. Poll med en slutfrist, ikke en antaget netværkspause.
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            WireClient client = new WireClient(port);
            try {
                client.send("LOGIN||" + name);
                ServerMessage response = client.read();
                if (response.type() == LOGIN_OK) return client;
                assertEquals(ERROR, response.type());
            } catch (Exception exception) {
                client.close();
                throw exception;
            }
            client.close();
            Thread.sleep(10);
        }
        throw new AssertionError("Brugernavn eller kapacitet blev ikke frigivet inden for tidsfristen.");
    }

    private static final class WireClient implements AutoCloseable {
        private final Socket socket = new Socket();
        private final BufferedReader input;
        private final BufferedWriter output;

        WireClient(int port) throws IOException {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000);
            socket.setSoTimeout(2_000);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        void send(String line) throws IOException {
            output.write(line);
            output.newLine();
            output.flush();
        }

        ServerMessage read() throws IOException {
            String line = input.readLine();
            assertNotNull(line, "Forbindelsen sluttede før den forventede besked.");
            return MessageParser.parseServer(line);
        }

        String readLine() throws IOException { return input.readLine(); }

        public void close() throws IOException { socket.close(); }
    }
}
