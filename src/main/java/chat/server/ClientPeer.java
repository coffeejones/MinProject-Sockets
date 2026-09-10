package chat.server;

import chat.protocol.ServerMessage;

import java.io.IOException;

/** Et modtager-endepunkt, så chatlogikken også kan testes uden sockets. */
public interface ClientPeer extends AutoCloseable {
    void send(ServerMessage message) throws IOException;

    @Override
    void close();
}
