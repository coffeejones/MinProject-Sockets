package chat.server;

import chat.protocol.ServerMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChatStateTest {
    @Test
    void concurrentRegistrationsHaveExactlyOneWinner() throws Exception {
        ChatState state = new ChatState();
        ClientPeer first = new TestPeer();
        ClientPeer second = new TestPeer();
        state.connected(first);
        state.connected(second);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(2, TimeUnit.SECONDS));
                return state.register(first, "alice");
            });
            var secondResult = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(2, TimeUnit.SECONDS));
                return state.register(second, "alice");
            });
            try {
                assertTrue(ready.await(2, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            List<ChatState.LoginResult> results = List.of(
                    firstResult.get(2, TimeUnit.SECONDS), secondResult.get(2, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(result -> result == ChatState.LoginResult.SUCCESS).count());
            assertEquals(1, results.stream().filter(result -> result == ChatState.LoginResult.NAME_TAKEN).count());
            assertEquals(1, state.sessions().size());
            assertEquals(1, state.recipients(state.user("alice").orElseThrow(), "lobby").orElseThrow().size());
        }
    }

    @Test
    void snapshotsAreImmutableAndDoNotChangeAfterRoomMove() {
        ChatState state = new ChatState();
        ClientPeer alice = new TestPeer();
        state.connected(alice);
        state.register(alice, "alice");
        var sessions = state.sessions();
        var recipients = state.recipients(alice, "lobby").orElseThrow();
        assertThrows(UnsupportedOperationException.class, sessions::clear);
        assertThrows(UnsupportedOperationException.class, recipients::clear);
        assertEquals(ChatState.MoveResult.SUCCESS, state.move(alice, "java"));
        assertEquals("lobby", sessions.getFirst().room());
        assertEquals(List.of(alice), recipients);
        assertTrue(state.recipients(alice, "lobby").isEmpty());
        assertEquals("java", state.session(alice).orElseThrow().room());
    }

    @Test
    void disconnectBeforeLoginCannotCreateOrphanedUser() {
        ChatState state = new ChatState();
        ClientPeer peer = new TestPeer();
        state.connected(peer);
        state.disconnected(peer);
        assertEquals(ChatState.LoginResult.DISCONNECTED, state.register(peer, "alice"));
        assertTrue(state.sessions().isEmpty());
        assertTrue(state.user("alice").isEmpty());
    }

    private static final class TestPeer implements ClientPeer {
        public void send(ServerMessage message) { }
        public void close() { }
    }
}
