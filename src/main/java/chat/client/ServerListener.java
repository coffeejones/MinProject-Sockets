package chat.client;

import chat.protocol.MessageParser;
import chat.protocol.ProtocolException;
import chat.protocol.ServerMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.function.Consumer;

/** Læser på sin egen tråd, så modtagelse aldrig optager klientens sendetråd. */
public final class ServerListener implements Runnable {
    private final BufferedReader input;
    private final Consumer<ServerMessage> messages;
    private final Consumer<Exception> failures;
    private final Runnable finished;

    ServerListener(BufferedReader input, Consumer<ServerMessage> messages,
                   Consumer<Exception> failures, Runnable finished) {
        this.input = input;
        this.messages = messages;
        this.failures = failures;
        this.finished = finished;
    }

    @Override
    public void run() {
        try {
            String line;
            while ((line = input.readLine()) != null) {
                messages.accept(MessageParser.parseServer(line));
            }
        } catch (IOException | ProtocolException exception) {
            failures.accept(exception);
        } finally {
            finished.run();
        }
    }
}
