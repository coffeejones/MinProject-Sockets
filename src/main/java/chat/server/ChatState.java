package chat.server;

import chat.protocol.ChatProtocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Alle bruger- og rumdata beskyttes af den samme lås. Ingen I/O udføres her. */
public final class ChatState {
    public enum LoginResult { SUCCESS, NAME_TAKEN, ALREADY_LOGGED_IN, DISCONNECTED }

    public enum MoveResult { SUCCESS, UNKNOWN_ROOM, NOT_LOGGED_IN }

    public record Session(String username, String room, ClientPeer peer) { }

    private static final class Membership {
        private String username;
        private String room;
    }

    private final Object lock = new Object();
    // Identity sikrer, at oprydning altid angår præcis den konkrete forbindelse.
    private final Map<ClientPeer, Membership> connections = new IdentityHashMap<>();
    private final Map<String, ClientPeer> users = new HashMap<>();
    private final Map<String, Set<ClientPeer>> rooms = new HashMap<>();

    public ChatState() {
        for (String room : ChatProtocol.ROOMS) {
            rooms.put(room, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        }
    }

    public void connected(ClientPeer peer) {
        synchronized (lock) {
            connections.putIfAbsent(peer, new Membership());
        }
    }

    public LoginResult register(ClientPeer peer, String username) {
        synchronized (lock) {
            Membership membership = connections.get(peer);
            if (membership == null) return LoginResult.DISCONNECTED;
            if (membership.username != null) return LoginResult.ALREADY_LOGGED_IN;
            if (users.containsKey(username)) return LoginResult.NAME_TAKEN;
            membership.username = username;
            membership.room = ChatProtocol.START_ROOM;
            users.put(username, peer);
            rooms.get(membership.room).add(peer);
            return LoginResult.SUCCESS;
        }
    }

    public Optional<Session> session(ClientPeer peer) {
        synchronized (lock) {
            Membership membership = connections.get(peer);
            if (membership == null || membership.username == null) return Optional.empty();
            return Optional.of(new Session(membership.username, membership.room, peer));
        }
    }

    public MoveResult move(ClientPeer peer, String room) {
        synchronized (lock) {
            Membership membership = connections.get(peer);
            if (membership == null || membership.username == null) return MoveResult.NOT_LOGGED_IN;
            if (!rooms.containsKey(room)) return MoveResult.UNKNOWN_ROOM;
            rooms.get(membership.room).remove(peer);
            rooms.get(room).add(peer);
            membership.room = room;
            return MoveResult.SUCCESS;
        }
    }

    /** Validerer medlemskab og kopierer modtagere i én atomisk operation. */
    public Optional<List<ClientPeer>> recipients(ClientPeer sender, String room) {
        synchronized (lock) {
            Membership membership = connections.get(sender);
            if (membership == null || !room.equals(membership.room)) return Optional.empty();
            return Optional.of(List.copyOf(rooms.get(room)));
        }
    }

    public Optional<ClientPeer> user(String username) {
        synchronized (lock) {
            return Optional.ofNullable(users.get(username));
        }
    }

    public List<Session> sessions() {
        synchronized (lock) {
            List<Session> snapshot = new ArrayList<>();
            connections.forEach((peer, membership) -> {
                if (membership.username != null) {
                    snapshot.add(new Session(membership.username, membership.room, peer));
                }
            });
            return List.copyOf(snapshot);
        }
    }

    public void disconnected(ClientPeer peer) {
        synchronized (lock) {
            Membership membership = connections.remove(peer);
            if (membership == null || membership.username == null) return;
            if (users.get(membership.username) == peer) users.remove(membership.username);
            rooms.get(membership.room).remove(peer);
        }
    }
}
