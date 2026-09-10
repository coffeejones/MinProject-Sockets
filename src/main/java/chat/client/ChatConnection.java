package chat.client;

import chat.protocol.ClientMessage;
import chat.protocol.MessageParser;
import chat.protocol.MessageType;
import chat.protocol.ServerMessage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Én forbindelse; ved genforbindelse oprettes en ny ChatConnection. */
public final class ChatConnection implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    private static final int QUIT_TIMEOUT_SECONDS = 2;

    private final ConnectionListener listener;
    private final ExecutorService outgoing = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("chat-client-send").factory());
    private final AtomicReference<Socket> socket = new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean quitting = new AtomicBoolean();
    private final Set<CompletableFuture<Void>> pending = ConcurrentHashMap.newKeySet();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private volatile BufferedWriter output;

    public ChatConnection(ConnectionListener listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    public CompletableFuture<Void> connect(String host, int port) {
        if (host == null || host.isBlank() || port < 1 || port > 65_535) {
            return invalid("Angiv en serveradresse og en port mellem 1 og 65535.");
        }
        if (!started.compareAndSet(false, true)) {
            return invalid("Denne forbindelse er allerede startet. Opret en ny forbindelse.");
        }
        return enqueue(() -> {
            Socket newSocket = new Socket();
            // Publicér før connect: close() kan så afbryde et igangværende forsøg.
            socket.set(newSocket);
            if (closed.get()) {
                newSocket.close();
                throw new IOException("Forbindelsen er lukket.");
            }
            newSocket.connect(new InetSocketAddress(host.strip(), port), CONNECT_TIMEOUT_MILLIS);
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    newSocket.getInputStream(), StandardCharsets.UTF_8));
            output = new BufferedWriter(new OutputStreamWriter(
                    newSocket.getOutputStream(), StandardCharsets.UTF_8));
            if (closed.get()) {
                return;
            }
            notifyListener(listener::onConnected);
            if (!closed.get()) {
                Thread.ofPlatform().daemon().name("chat-client-listen")
                        .start(new ServerListener(input, this::receive,
                                this::readFailed, this::readEnded));
            }
        });
    }

    public CompletableFuture<Void> send(ClientMessage message) {
        if (closed.get() || quitting.get()) {
            return CompletableFuture.failedFuture(new IOException("Forbindelsen er lukket."));
        }
        final String line;
        try {
            line = MessageParser.format(message);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return invalid(exception.getMessage());
        }
        if (!started.get()) {
            return invalid("Forbind til serveren først.");
        }
        return enqueue(() -> write(line));
    }

    /** Sender QUIT og venter kort på BYE eller EOF uden at blokere brugerfladen. */
    public void quit() {
        if (closed.get() || !quitting.compareAndSet(false, true)) {
            return;
        }
        if (output == null) {
            finish("Forbindelsen er lukket.", false);
            return;
        }
        enqueue(() -> write(MessageParser.format(new ClientMessage(MessageType.QUIT, "", ""))));
        // Fristen gælder også, hvis selve skrivningen bliver blokeret.
        CompletableFuture.delayedExecutor(QUIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .execute(() -> finish("Forbindelsen er lukket.", false));
    }

    @Override
    public void close() {
        quitting.set(true);
        finish("Forbindelsen er lukket.", false);
    }

    public boolean isClosed() {
        return closed.get();
    }

    public CompletableFuture<Void> completion() {
        return completion;
    }

    private void write(String line) throws IOException {
        BufferedWriter writer = output;
        if (writer == null) {
            throw new IOException("Der er ingen forbindelse til serveren.");
        }
        // Kun outgoing-tråden skriver: linje, linjeskift og flush sker samlet.
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    private CompletableFuture<Void> enqueue(IoOperation operation) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (closed.get()) {
            result.completeExceptionally(new IOException("Forbindelsen er lukket."));
            return result;
        }
        pending.add(result);
        try {
            outgoing.execute(() -> {
                try {
                    if (closed.get()) {
                        throw new IOException("Forbindelsen er lukket.");
                    }
                    operation.run();
                    result.complete(null);
                } catch (IOException | RuntimeException exception) {
                    // Vis fejlen før future vækker fx konsol-main, som ellers kunne
                    // lukke forbindelsen og skjule den oprindelige fejl.
                    pending.remove(result);
                    if (!closed.get()) {
                        notifyListener(() -> listener.onError("Netværksfejl: " + detail(exception)));
                        finish("Forbindelsen blev afbrudt.", !quitting.get());
                    }
                    result.completeExceptionally(exception);
                } finally {
                    pending.remove(result);
                }
            });
        } catch (RejectedExecutionException exception) {
            pending.remove(result);
            result.completeExceptionally(new IOException("Forbindelsen er lukket.", exception));
        }
        return result;
    }

    private CompletableFuture<Void> invalid(String message) {
        String description = message == null ? "Ugyldig besked." : message;
        notifyListener(() -> listener.onError(description));
        return CompletableFuture.failedFuture(new IllegalArgumentException(description));
    }

    private void receive(ServerMessage message) {
        if (!closed.get()) {
            // Send alle beskeder videre, også en broadcast lige før LOGIN_OK.
            notifyListener(() -> listener.onMessage(message));
            if (message.type() == MessageType.BYE) {
                finish("Serveren afsluttede forbindelsen.", false);
            }
        }
    }

    private void readFailed(Exception exception) {
        if (!closed.get()) {
            if (!quitting.get()) {
                notifyListener(() -> listener.onError("Forbindelsesfejl: " + detail(exception)));
            }
            finish("Forbindelsen blev afbrudt.", !quitting.get());
        }
    }

    private void readEnded() {
        finish(quitting.get() ? "Forbindelsen er lukket." : "Serveren lukkede forbindelsen.",
                !quitting.get());
    }

    private void finish(String reason, boolean unexpected) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Socket current = socket.get();
        if (current != null) {
            try {
                // Ingen sendelås her: lukning skal kunne afbryde en blokeret skrivning.
                current.close();
            } catch (IOException ignored) {
                // Forbindelsen er allerede på vej gennem samme oprydning.
            }
        }
        outgoing.shutdownNow();
        IOException failure = new IOException(reason);
        pending.forEach(future -> future.completeExceptionally(failure));
        pending.clear();
        notifyListener(() -> listener.onDisconnected(reason, unexpected));
        completion.complete(null);
    }

    private void notifyListener(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException ignored) {
            // En fejl i brugerfladens callback må ikke forhindre netværksoprydning.
        }
    }

    private static String detail(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws IOException;
    }
}
