package chat.client;

import chat.client.console.ChatClient;
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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Starter den rigtige konsolklient med pipet input og en lille testserver. */
@Timeout(10)
class ConsoleProcessTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 10, 15, 0);

    @Test
    void failedPrivateThenRoomChangeWaitsForEachOwnReply() throws Exception {
        try (ServerSocket server = server(); RunningConsole console = new RunningConsole(server.getLocalPort());
             Socket peer = server.accept()) {
            peer.setSoTimeout(2_000);
            BufferedReader input = reader(peer);
            BufferedWriter output = writer(peer);
            console.input.write("bob\n/msg missing Hej\n/join java\nHej Java\n/quit\n");
            console.input.flush();
            assertEquals("LOGIN||bob", input.readLine());
            send(output, MessageType.LOGIN_OK, "server", "lobby", "bob");
            assertEquals("PRIVATE|missing|Hej", input.readLine());

            // En fremmed besked må ikke frigive input, mens vores PRIVATE venter.
            send(output, MessageType.TEXT, "alice", "lobby", "En anden besked");
            assertNoCommandYet(peer, input);
            send(output, MessageType.ERROR, "server", "bob", "Modtageren er ikke online.");
            assertEquals("JOIN_ROOM|java|", input.readLine());
            assertNoCommandYet(peer, input);
            send(output, MessageType.ROOM_JOINED, "server", "java", "Du er nu i java.");
            assertEquals("TEXT|java|Hej Java", input.readLine());

            send(output, MessageType.TEXT, "alice", "java", "Hej Bob");
            assertNoCommandYet(peer, input);
            send(output, MessageType.TEXT, "bob", "java", "Hej Java");
            assertEquals("QUIT||", input.readLine());
            send(output, MessageType.BYE, "server", "bob", "Farvel.");
            assertTrue(console.process.waitFor(2, TimeUnit.SECONDS));
            assertEquals(0, console.process.exitValue());
            String displayed = console.output();
            assertTrue(displayed.contains("Modtageren er ikke online."));
            assertTrue(displayed.contains("[java] bob: Hej Java"));
            assertTrue(displayed.contains("[lobby] alice: En anden besked"));
        }
    }

    @Test
    void canRetryUsernameOnSameSocketAndQuitAtInputEof() throws Exception {
        try (ServerSocket server = server(); RunningConsole console = new RunningConsole(server.getLocalPort());
             Socket peer = server.accept()) {
            peer.setSoTimeout(2_000);
            BufferedReader input = reader(peer);
            BufferedWriter output = writer(peer);
            console.input.write("bob\nalice\n");
            console.input.close();
            assertEquals("LOGIN||bob", input.readLine());
            send(output, MessageType.ERROR, "server", "", "Brugernavnet er optaget.");
            assertEquals("LOGIN||alice", input.readLine());
            send(output, MessageType.LOGIN_OK, "server", "lobby", "alice");
            assertEquals("QUIT||", input.readLine());
            send(output, MessageType.BYE, "server", "alice", "Farvel.");
            assertTrue(console.process.waitFor(2, TimeUnit.SECONDS));
            assertEquals(0, console.process.exitValue());
            String displayed = console.output();
            assertTrue(displayed.contains("Brugernavnet er optaget."));
            assertTrue(displayed.contains("Logget ind som alice"));
        }
    }

    @Test
    void serverEofStopsProcessWhileStdinRemainsOpen() throws Exception {
        try (ServerSocket server = server(); RunningConsole console = new RunningConsole(server.getLocalPort())) {
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(2_000);
                console.input.write("bob\n");
                console.input.flush();
                assertEquals("LOGIN||bob", reader(peer).readLine());
                send(writer(peer), MessageType.LOGIN_OK, "server", "lobby", "bob");
            }
            // Vi lukker ikke stdin og sender ingen ekstra Enter.
            assertTrue(console.process.waitFor(2, TimeUnit.SECONDS));
            assertEquals(0, console.process.exitValue());
            assertTrue(console.output().contains("Serveren lukkede forbindelsen."));
        }
    }

    private static void assertNoCommandYet(Socket peer, BufferedReader input) throws Exception {
        peer.setSoTimeout(250);
        try {
            assertThrows(SocketTimeoutException.class, input::readLine);
        } finally {
            peer.setSoTimeout(2_000);
        }
    }

    private static ServerSocket server() throws Exception {
        ServerSocket server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(3_000);
        return server;
    }

    private static BufferedReader reader(Socket socket) throws Exception {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private static BufferedWriter writer(Socket socket) throws Exception {
        return new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    private static void send(BufferedWriter output, MessageType type, String sender,
                             String target, String payload) throws Exception {
        output.write(MessageParser.format(new ServerMessage(NOW, type, sender, target, payload)));
        output.newLine();
        output.flush();
    }

    private static final class RunningConsole implements AutoCloseable {
        private final Process process;
        private final BufferedWriter input;

        private RunningConsole(int port) throws Exception {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classes = Path.of(ChatClient.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString();
            process = new ProcessBuilder(java, "-cp", classes, ChatClient.class.getName(),
                    "127.0.0.1", Integer.toString(port)).redirectErrorStream(true).start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }

        private String output() throws Exception {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws Exception {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            process.getInputStream().close();
            process.getOutputStream().close();
        }
    }
}
