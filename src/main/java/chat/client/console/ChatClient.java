package chat.client.console;

import chat.client.ChatConnection;
import chat.client.ConnectionListener;
import chat.protocol.ChatProtocol;
import chat.protocol.ClientMessage;
import chat.protocol.MessageType;
import chat.protocol.ServerMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionException;

/** Konsolvisning med samme netværkskode som JavaFX-klienten. */
public final class ChatClient implements ConnectionListener {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Object stateLock = new Object();
    private final ChatConnection connection = new ChatConnection(this);
    private String username;
    private String room;
    private MessageType awaitingReply;

    public static void main(String[] args) {
        if (args.length > 2) {
            System.err.println("Brug: ChatClient [serveradresse] [port]");
            return;
        }
        String host = args.length > 0 ? args[0] : ChatProtocol.DEFAULT_HOST;
        int port;
        try {
            port = args.length > 1 ? Integer.parseInt(args[1]) : ChatProtocol.DEFAULT_PORT;
            if (port < 1 || port > 65_535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            System.err.println("Port skal være et tal mellem 1 og 65535.");
            return;
        }
        new ChatClient().run(host, port);
    }

    private void run(String host, int port) {
        try (connection) {
            try {
                connection.connect(host, port).join();
            } catch (CompletionException exception) {
                return; // ConnectionListener har allerede vist fejlen.
            }
            // System.in kan blokere. Tråden må ikke holde processen åben ved serverstop.
            Thread.ofPlatform().daemon().name("chat-console-input").start(this::readConsole);
            connection.completion().join();
        }
    }

    private void readConsole() {
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            while (!connection.isClosed()) {
                boolean needsLogin;
                synchronized (stateLock) {
                    while (awaitingReply != null && !connection.isClosed()) {
                        stateLock.wait();
                    }
                    if (connection.isClosed()) {
                        return;
                    }
                    needsLogin = username == null;
                    System.out.print(needsLogin ? "Vælg brugernavn: " : room + "> ");
                    System.out.flush();
                }
                String line = console.readLine();
                if (line == null || line.strip().equals("/quit")) {
                    connection.quit();
                    return;
                }
                if (line.isBlank()) {
                    continue;
                }
                if (needsLogin) {
                    send(new ClientMessage(MessageType.LOGIN, "", line.strip()), MessageType.LOGIN_OK);
                } else {
                    handleInput(line);
                }
            }
        } catch (IOException exception) {
            onError("Kunne ikke læse fra konsollen: " + exception.getMessage());
            connection.quit();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            connection.close();
        }
    }

    private void handleInput(String line) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("/")) {
            String target;
            synchronized (stateLock) {
                target = room;
            }
            send(new ClientMessage(MessageType.TEXT, target, line), MessageType.TEXT);
            return;
        }
        String[] command = trimmed.split("\\s+", 3);
        switch (command[0]) {
            case "/help" -> printHelp();
            case "/join" -> {
                if (command.length != 2) {
                    onError("Brug: /join java");
                } else {
                    send(new ClientMessage(MessageType.JOIN_ROOM, command[1], ""), MessageType.ROOM_JOINED);
                }
            }
            case "/msg" -> {
                if (command.length != 3) {
                    onError("Brug: /msg alice Hej Alice");
                } else {
                    send(new ClientMessage(MessageType.PRIVATE, command[1], command[2]), MessageType.INFO);
                }
            }
            default -> onError("Ukendt kommando. Skriv /help for hjælp.");
        }
    }

    private void send(ClientMessage message, MessageType expectedReply) {
        // Én egen kommando ad gangen gør ERROR entydig uden request-id i protokollen.
        synchronized (stateLock) {
            awaitingReply = expectedReply;
        }
        connection.send(message).whenComplete((ignored, failure) -> {
            if (failure != null) {
                releaseInput();
            }
        });
    }

    @Override
    public void onConnected() {
        System.out.println("Forbundet til serveren. Vælg et brugernavn, eller skriv /quit.");
    }

    @Override
    public void onMessage(ServerMessage message) {
        switch (message.type()) {
            case LOGIN_OK -> {
                synchronized (stateLock) {
                    username = message.payload();
                    room = message.target();
                }
                System.out.println("\nLogget ind som " + message.payload() + " i " + message.target() + ".");
                printHelp();
                releaseInput();
            }
            case ROOM_JOINED -> {
                synchronized (stateLock) {
                    room = message.target();
                }
                System.out.println("\nDu er nu i " + message.target() + ".");
                releaseInput();
            }
            case TEXT -> {
                System.out.printf("%n[%s] [%s] %s: %s%n", TIME.format(message.timestamp()),
                        message.target(), message.sender(), message.payload());
                synchronized (stateLock) {
                    if (message.sender().equals(username) && awaitingReply == MessageType.TEXT) {
                        releaseInput();
                    }
                }
            }
            case PRIVATE -> System.out.printf("%n[%s] [Privat fra %s] %s%n", TIME.format(message.timestamp()),
                    message.sender(), message.payload());
            case ERROR -> {
                onError(message.payload());
                releaseInput();
            }
            case INFO -> {
                System.out.println("\n" + message.payload());
                synchronized (stateLock) {
                    if (awaitingReply == MessageType.INFO) {
                        releaseInput();
                    }
                }
            }
            case BYE -> { /* Afslutningen vises samlet i onDisconnected. */ }
            default -> onError("Uventet beskedtype fra serveren: " + message.type());
        }
    }

    @Override
    public void onError(String message) {
        System.out.println("\nFejl: " + message);
    }

    @Override
    public void onDisconnected(String reason, boolean unexpected) {
        System.out.println("\n" + reason);
        releaseInput();
    }

    private void releaseInput() {
        synchronized (stateLock) {
            awaitingReply = null;
            stateLock.notifyAll();
        }
    }

    private static void printHelp() {
        System.out.println("Skriv tekst til rummet, /join RUM, /msg BRUGER TEKST, /help eller /quit.");
        System.out.println("Rum: " + String.join(", ", ChatProtocol.ROOMS));
    }
}
