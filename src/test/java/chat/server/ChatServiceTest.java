package chat.server;

import chat.protocol.ClientMessage;
import chat.protocol.MessageType;
import chat.protocol.ServerMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static chat.protocol.MessageType.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatServiceTest {
    private final ChatState state = new ChatState();
    private final ChatService service = new ChatService(state);

    @Test
    void loginNormalizesNamesAndAllowsRetryAfterDuplicate() {
        TestPeer alice = connected();
        TestPeer duplicate = connected();
        handle(alice, LOGIN, "", "Alice");
        assertEquals("alice", alice.last().payload());
        assertEquals(LOGIN_OK, alice.last().type());
        assertEquals("lobby", alice.last().target());
        handle(duplicate, LOGIN, "", "ALICE");
        assertEquals(ERROR, duplicate.last().type());
        handle(duplicate, LOGIN, "", "bob");
        assertEquals(LOGIN_OK, duplicate.last().type());
        assertEquals(2, state.sessions().size());
    }

    @Test
    void validatesUsernamesAndRequiresLogin() {
        TestPeer peer = connected();
        handle(peer, TEXT, "lobby", "Hej");
        assertEquals(ERROR, peer.last().type());
        assertEquals("", peer.last().target());
        for (String name : List.of("server", "SERVER", "ønske", "with space", "x".repeat(21), "user!")) {
            handle(peer, LOGIN, "", name);
            assertEquals(ERROR, peer.last().type(), name);
            assertTrue(state.sessions().isEmpty());
        }
        handle(peer, LOGIN, "", "valid_123");
        handle(peer, LOGIN, "", "another");
        assertEquals(ERROR, peer.last().type());
        assertEquals("valid_123", state.session(peer).orElseThrow().username());
    }

    @Test
    void roomsAreIsolatedAndSenderReceivesMessageExactlyOnce() {
        TestPeer alice = login("alice");
        TestPeer bob = login("bob");
        TestPeer charlie = login("charlie");
        handle(charlie, JOIN_ROOM, "java", "");
        assertEquals(ROOM_JOINED, charlie.last().type());
        assertEquals("java", charlie.last().target());
        clear(alice, bob, charlie);

        handle(bob, TEXT, "lobby", "Hej | æøå");
        assertEquals(1, alice.messages.size());
        assertEquals(1, bob.messages.size());
        assertTrue(charlie.messages.isEmpty());
        assertEquals("bob", alice.last().sender());
        assertEquals("Hej | æøå", alice.last().payload());
        assertEquals(TEXT, bob.last().type());
    }

    @Test
    void invalidRoomMoveAndForeignRoomTextPreserveMembership() {
        TestPeer alice = login("alice");
        handle(alice, JOIN_ROOM, "does_not_exist", "");
        assertEquals(ERROR, alice.last().type());
        assertEquals("lobby", state.session(alice).orElseThrow().room());
        handle(alice, TEXT, "java", "Forkert rum");
        assertEquals(ERROR, alice.last().type());
        handle(alice, JOIN_ROOM, "lobby", "");
        assertEquals(ROOM_JOINED, alice.last().type());
        assertEquals(List.of(alice), state.recipients(alice, "lobby").orElseThrow());
    }

    @Test
    void privateMessagesCrossRoomsAndOnlyRecipientReceivesTheirText() {
        TestPeer alice = login("alice");
        TestPeer bob = login("bob");
        TestPeer charlie = login("charlie");
        handle(charlie, JOIN_ROOM, "java", "");
        clear(alice, bob, charlie);
        handle(alice, PRIVATE, "CHARLIE", "Hemmelig besked");
        assertEquals(1, charlie.messages.size());
        assertEquals(PRIVATE, charlie.last().type());
        assertEquals("alice", charlie.last().sender());
        assertEquals("charlie", charlie.last().target());
        assertEquals("Hemmelig besked", charlie.last().payload());
        assertEquals(List.of(INFO), alice.messages.stream().map(ServerMessage::type).toList());
        assertFalse(alice.last().payload().contains("Hemmelig"));
        assertTrue(bob.messages.isEmpty());
        handle(alice, PRIVATE, "offline", "Hej");
        assertEquals(ERROR, alice.last().type());
    }

    @Test
    void failedWriteRemovesOnlyBrokenRecipientAndBroadcastContinues() {
        TestPeer alice = login("alice");
        TestPeer broken = login("broken");
        TestPeer bob = login("bob");
        clear(alice, broken, bob);
        broken.failWrites = true;
        handle(alice, TEXT, "lobby", "Hej");
        assertEquals(TEXT, alice.last().type());
        assertEquals(TEXT, bob.last().type());
        assertTrue(broken.closed);
        assertTrue(state.user("broken").isEmpty());
    }

    @Test
    void failedPrivateWriteReturnsErrorInsteadOfSuccess() {
        TestPeer alice = login("alice");
        TestPeer bob = login("bob");
        bob.failWrites = true;
        alice.messages.clear();
        handle(alice, PRIVATE, "bob", "Hej");
        assertEquals(List.of(ERROR), alice.messages.stream().map(ServerMessage::type).toList());
        assertTrue(state.user("bob").isEmpty());
    }

    @Test
    void slowRecipientDoesNotHoldTheSharedStateLock() throws Exception {
        TestPeer alice = login("alice");
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch allowWrite = new CountDownLatch(1);
        ClientPeer slow = new ClientPeer() {
            public void send(ServerMessage message) throws IOException {
                if (message.type() != TEXT) return;
                writeStarted.countDown();
                try {
                    if (!allowWrite.await(3, TimeUnit.SECONDS)) throw new IOException("Tidsfrist udløbet");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
            }
            public void close() { }
        };
        service.connected(slow);
        service.handle(slow, new ClientMessage(LOGIN, "", "slow"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var broadcast = executor.submit(() -> handle(alice, TEXT, "lobby", "Hej"));
            try {
                assertTrue(writeStarted.await(2, TimeUnit.SECONDS));
                // Et login skal kunne afsluttes, mens modtagerens skrivning er blokeret.
                var newLogin = executor.submit(() -> login("bob"));
                assertEquals(LOGIN_OK, newLogin.get(1, TimeUnit.SECONDS).last().type());
            } finally {
                allowWrite.countDown();
            }
            broadcast.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void quitWorksBeforeLoginAndDisconnectIsIdempotent() {
        TestPeer anonymous = connected();
        handle(anonymous, QUIT, "", "");
        assertTrue(anonymous.closed);
        assertEquals(BYE, anonymous.last().type());
        assertEquals("", anonymous.last().target());
        TestPeer alice = login("alice");
        handle(alice, QUIT, "", "");
        assertEquals(BYE, alice.last().type());
        assertEquals("alice", alice.last().target());
        service.disconnected(alice);
        assertTrue(state.sessions().isEmpty());
        TestPeer replacement = login("alice");
        service.disconnected(alice);
        assertSame(replacement, state.user("alice").orElseThrow());
    }

    private TestPeer connected() {
        TestPeer peer = new TestPeer();
        service.connected(peer);
        return peer;
    }

    private TestPeer login(String username) {
        TestPeer peer = connected();
        handle(peer, LOGIN, "", username);
        assertEquals(LOGIN_OK, peer.last().type());
        return peer;
    }

    private void handle(TestPeer peer, MessageType type, String target, String payload) {
        service.handle(peer, new ClientMessage(type, target, payload));
    }

    private void clear(TestPeer... peers) {
        for (TestPeer peer : peers) peer.messages.clear();
    }

    private static final class TestPeer implements ClientPeer {
        final List<ServerMessage> messages = new ArrayList<>();
        boolean closed;
        boolean failWrites;

        public void send(ServerMessage message) throws IOException {
            if (failWrites) throw new IOException("Simuleret skrivefejl");
            messages.add(message);
        }

        public void close() { closed = true; }

        ServerMessage last() { return messages.getLast(); }
    }
}
