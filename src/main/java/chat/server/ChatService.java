package chat.server;

import chat.protocol.ChatProtocol;
import chat.protocol.ClientMessage;
import chat.protocol.MessageType;
import chat.protocol.ServerMessage;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Udfører kommandoer. Tilstandslåsen holdes aldrig under netværksskrivning. */
public final class ChatService {
    private final ChatState state;

    public ChatService() {
        this(new ChatState());
    }

    public ChatService(ChatState state) {
        this.state = state;
    }

    public void connected(ClientPeer peer) {
        state.connected(peer);
    }

    public void disconnected(ClientPeer peer) {
        state.disconnected(peer);
    }

    public void handle(ClientPeer peer, ClientMessage command) {
        if (command.type() == MessageType.QUIT) {
            deliver(peer, response(MessageType.BYE, username(peer), "Forbindelsen afsluttes."));
            disconnected(peer);
            peer.close();
            return;
        }
        if (command.type() == MessageType.LOGIN) {
            login(peer, command.payload());
            return;
        }
        Optional<ChatState.Session> session = state.session(peer);
        if (session.isEmpty()) {
            error(peer, "Du skal logge ind først.");
            return;
        }
        switch (command.type()) {
            case JOIN_ROOM -> join(peer, command.target());
            case TEXT -> broadcast(peer, session.get(), command);
            case PRIVATE -> privateMessage(peer, session.get(), command);
            default -> error(peer, "Denne beskedtype kan ikke sendes af en klient.");
        }
    }

    public void error(ClientPeer peer, String text) {
        deliver(peer, response(MessageType.ERROR, username(peer), text));
    }

    private void login(ClientPeer peer, String requestedName) {
        String name = requestedName.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_]{1,20}") || name.equals("server")) {
            error(peer, "Brug 1–20 tegn: a-z, 0-9 og _. Navnet server er reserveret.");
            return;
        }
        switch (state.register(peer, name)) {
            case SUCCESS -> deliver(peer, response(MessageType.LOGIN_OK, ChatProtocol.START_ROOM, name));
            case NAME_TAKEN -> error(peer, "Brugernavnet er allerede i brug. Vælg et andet.");
            case ALREADY_LOGGED_IN -> error(peer, "Du er allerede logget ind.");
            case DISCONNECTED -> { /* Forbindelsen blev lukket, før login blev gennemført. */ }
        }
    }

    private void join(ClientPeer peer, String room) {
        switch (state.move(peer, room)) {
            case SUCCESS -> deliver(peer, response(MessageType.ROOM_JOINED, room, "Du er nu i " + room + "."));
            case UNKNOWN_ROOM -> error(peer, "Rummet findes ikke. Vælg mellem: " + String.join(", ", ChatProtocol.ROOMS) + ".");
            case NOT_LOGGED_IN -> error(peer, "Du skal logge ind først.");
        }
    }

    private void broadcast(ClientPeer peer, ChatState.Session session, ClientMessage command) {
        if (command.payload().isBlank()) {
            error(peer, "Beskeden må ikke være tom.");
            return;
        }
        Optional<List<ClientPeer>> recipients = state.recipients(peer, command.target());
        if (recipients.isEmpty()) {
            error(peer, "Du kan kun sende til dit aktuelle rum.");
            return;
        }
        ServerMessage message = new ServerMessage(LocalDateTime.now(), MessageType.TEXT,
                session.username(), command.target(), command.payload());
        for (ClientPeer recipient : recipients.get()) deliver(recipient, message);
    }

    private void privateMessage(ClientPeer peer, ChatState.Session session, ClientMessage command) {
        if (command.payload().isBlank()) {
            error(peer, "Beskeden må ikke være tom.");
            return;
        }
        String name = command.target().toLowerCase(Locale.ROOT);
        Optional<ClientPeer> recipient = state.user(name);
        if (recipient.isEmpty()) {
            error(peer, "Brugeren " + name + " er ikke online.");
            return;
        }
        ServerMessage message = new ServerMessage(LocalDateTime.now(), MessageType.PRIVATE,
                session.username(), name, command.payload());
        if (deliver(recipient.get(), message)) {
            deliver(peer, response(MessageType.INFO, session.username(), "Privat besked sendt til " + name + "."));
        } else {
            error(peer, "Beskeden kunne ikke leveres til " + name + ".");
        }
    }

    private String username(ClientPeer peer) {
        return state.session(peer).map(ChatState.Session::username).orElse("");
    }

    private ServerMessage response(MessageType type, String target, String payload) {
        return new ServerMessage(LocalDateTime.now(), type, "server", target, payload);
    }

    private boolean deliver(ClientPeer peer, ServerMessage message) {
        try {
            peer.send(message);
            return true;
        } catch (IOException exception) {
            disconnected(peer);
            peer.close();
            return false;
        }
    }
}
