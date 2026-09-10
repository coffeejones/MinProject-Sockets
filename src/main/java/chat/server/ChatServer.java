package chat.server;

import chat.protocol.ChatProtocol;
import chat.protocol.MessageParser;
import chat.protocol.MessageType;
import chat.protocol.ServerMessage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Accepterer forbindelser og ejer serverens sockets og faste trådpulje. */
public final class ChatServer implements AutoCloseable {
    public static final int DEFAULT_MAX_CLIENTS = 10;

    private final ServerSocket serverSocket;
    private final ExecutorService handlers;
    private final ChatService service = new ChatService();
    private final Object lifecycleLock = new Object();
    private final Set<ClientHandler> connections = new HashSet<>();
    private final Set<Socket> acceptedSockets = new HashSet<>();
    private final int maxClients;
    private boolean started;
    private boolean closed;

    public ChatServer(int port) throws IOException {
        this(port, DEFAULT_MAX_CLIENTS);
    }

    public ChatServer(int port, int maxClients) throws IOException {
        this(InetAddress.getLoopbackAddress().getHostAddress(), port, maxClients);
    }

    public ChatServer(String bindHost, int port, int maxClients) throws IOException {
        if (maxClients < 1) throw new IllegalArgumentException("Der skal være plads til mindst én klient.");
        this.maxClients = maxClients;
        serverSocket = new ServerSocket();
        try {
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName(bindHost), port));
        } catch (IOException | RuntimeException exception) {
            serverSocket.close();
            throw exception;
        }
        // Puljen og kapacitetsgrænsen har samme størrelse; standarden er 10.
        handlers = Executors.newFixedThreadPool(maxClients);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("Serveren er lukket.");
            if (started) return;
            started = true;
            new Thread(this::acceptConnections, "chat-server-accept").start();
        }
    }

    private void acceptConnections() {
        try {
            while (!isClosed()) {
                Socket socket = serverSocket.accept();
                accept(socket);
            }
        } catch (IOException exception) {
            if (!isClosed()) System.err.println("Serveren kunne ikke acceptere en forbindelse: " + exception.getMessage());
        } finally {
            close();
        }
    }

    private void accept(Socket socket) {
        ClientHandler handler = null;
        boolean reject = false;
        try {
            synchronized (lifecycleLock) {
                if (closed) {
                    socket.close();
                    return;
                }
                acceptedSockets.add(socket);
                if (connections.size() >= maxClients) {
                    reject = true;
                } else {
                    handler = new ClientHandler(socket, service, closedHandler -> release(closedHandler, socket));
                    connections.add(handler);
                }
            }
            if (reject) reject(socket);
            else handlers.execute(handler);
        } catch (IOException | RejectedExecutionException exception) {
            if (handler != null) handler.close();
            else closeSocket(socket);
        } finally {
            if (handler == null) {
                synchronized (lifecycleLock) {
                    acceptedSockets.remove(socket);
                }
            }
        }
    }

    private void reject(Socket socket) throws IOException {
        try (socket; BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            ServerMessage message = new ServerMessage(LocalDateTime.now(), MessageType.ERROR, "server", "",
                    "Serveren er fuld. Prøv igen senere (maks. " + maxClients + " klienter).");
            output.write(MessageParser.format(message));
            output.newLine();
            output.flush();
        }
    }

    private void release(ClientHandler handler, Socket socket) {
        synchronized (lifecycleLock) {
            connections.remove(handler);
            acceptedSockets.remove(socket);
        }
    }

    private boolean isClosed() {
        synchronized (lifecycleLock) {
            return closed;
        }
    }

    @Override
    public void close() {
        List<ClientHandler> toClose;
        List<Socket> socketsToClose;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            toClose = List.copyOf(connections);
            socketsToClose = List.copyOf(acceptedSockets);
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Fortsæt med at lukke samtlige klienter.
        }
        // Også en afvisningsbesked kan være i gang, når serveren stoppes.
        for (Socket socket : socketsToClose) closeSocket(socket);
        for (ClientHandler handler : toClose) handler.close();
        handlers.shutdownNow();
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Socketten kan allerede være lukket efter serverstop.
        }
    }

    public static void main(String[] args) {
        try {
            int port = args.length > 0 ? Integer.parseInt(args[0]) : ChatProtocol.DEFAULT_PORT;
            String host = args.length > 1 ? args[1] : ChatProtocol.DEFAULT_HOST;
            ChatServer server = new ChatServer(host, port, DEFAULT_MAX_CLIENTS);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "chat-server-shutdown"));
            server.start();
            System.out.println("Chatserver klar på " + host + ":" + server.port()
                    + " (maks. " + DEFAULT_MAX_CLIENTS + " klienter). Stop med Ctrl+C.");
            System.out.println("Tidsstempler bruger serverens lokale tidszone: " + java.time.ZoneId.systemDefault());
        } catch (IOException | IllegalArgumentException exception) {
            System.err.println("Serveren kunne ikke startes: " + exception.getMessage());
        }
    }
}
