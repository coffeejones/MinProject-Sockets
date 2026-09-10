package chat.server;

import chat.protocol.MessageParser;
import chat.protocol.ProtocolException;
import chat.protocol.ServerMessage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Én læseløkke pr. forbindelse. Flere handlers må sende til den samme modtager. */
public final class ClientHandler implements Runnable, ClientPeer {
    private final Socket socket;
    private final ChatService service;
    private final BufferedReader input;
    private final BufferedWriter output;
    private final Consumer<ClientHandler> onClosed;
    private final Object sendLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    ClientHandler(Socket socket, ChatService service, Consumer<ClientHandler> onClosed) throws IOException {
        this.socket = socket;
        this.service = service;
        this.onClosed = onClosed;
        input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        service.connected(this);
    }

    @Override
    public void run() {
        try {
            String line;
            while (!closed.get() && (line = input.readLine()) != null) {
                try {
                    service.handle(this, MessageParser.parseClient(line));
                } catch (ProtocolException exception) {
                    service.error(this, exception.getMessage());
                } catch (RuntimeException exception) {
                    // En fejl i én kommando må ikke stoppe accept-løkken eller andre klienter.
                    service.error(this, "Kommandoen kunne ikke behandles.");
                    System.err.println("Fejl i klientkommando: " + exception.getClass().getSimpleName());
                }
            }
        } catch (IOException exception) {
            // EOF, serverstop og I/O-fejl har samme oprydning i finally.
        } finally {
            close();
        }
    }

    @Override
    public void send(ServerMessage message) throws IOException {
        String line = MessageParser.format(message);
        synchronized (sendLock) {
            if (closed.get()) throw new IOException("Forbindelsen er lukket.");
            output.write(line);
            output.newLine();
            output.flush();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // Tag ikke sendLock: socket.close() skal kunne afbryde en blokeret skrivning.
        try {
            socket.close();
        } catch (IOException ignored) {
            // Registrene og kapacitetspladsen skal stadig frigives.
        } finally {
            service.disconnected(this);
            onClosed.accept(this);
        }
    }
}
