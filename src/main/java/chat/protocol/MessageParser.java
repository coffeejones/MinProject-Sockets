package chat.protocol;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;

/** Oversætter mellem beskedobjekter og én protokollinje uden afsluttende linjeskift. */
public final class MessageParser {
    // uuuu med STRICT afviser også umulige datoer som 30. februar.
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    private MessageParser() {
    }

    public static ClientMessage parseClient(String line) {
        requireSingleLine(line, "Protokollinjen");
        // En positiv grænse bevarer både tomt slutfelt og | inde i payload.
        String[] fields = line.split("\\|", 3);
        if (fields.length != 3) {
            throw new ProtocolException("En klientbesked skal have formatet TYPE|TARGET|PAYLOAD.");
        }
        ClientMessage message = new ClientMessage(parseType(fields[0]), fields[1], fields[2]);
        validateClient(message);
        return message;
    }

    public static ServerMessage parseServer(String line) {
        requireSingleLine(line, "Protokollinjen");
        String[] fields = line.split("\\|", 5);
        if (fields.length != 5) {
            throw new ProtocolException(
                    "En serverbesked skal have formatet TIMESTAMP|TYPE|SENDER|TARGET|PAYLOAD.");
        }
        ServerMessage message = new ServerMessage(parseTimestamp(fields[0]), parseType(fields[1]),
                fields[2], fields[3], fields[4]);
        validateServer(message);
        return message;
    }

    public static String format(ClientMessage message) {
        validateClient(message);
        return message.type() + "|" + message.target() + "|" + message.payload();
    }

    public static String format(ServerMessage message) {
        validateServer(message);
        return TIMESTAMP_FORMAT.format(message.timestamp()) + "|" + message.type() + "|"
                + message.sender() + "|" + message.target() + "|" + message.payload();
    }

    private static void validateClient(ClientMessage message) {
        if (message == null || message.type() == null) {
            throw new ProtocolException("Klientbeskeden og dens type skal være angivet.");
        }
        requireField(message.target(), "Target");
        requireSingleLine(message.payload(), "Payload");

        switch (message.type()) {
            case LOGIN -> {
                requireEmpty(message.target(), "Target ved LOGIN");
                requireText(message.payload(), "Brugernavn");
            }
            case JOIN_ROOM -> {
                requireText(message.target(), "Rum");
                requireEmpty(message.payload(), "Payload ved JOIN_ROOM");
            }
            case TEXT, PRIVATE -> {
                requireText(message.target(), "Target");
                requireText(message.payload(), "Beskedtekst");
            }
            case QUIT -> {
                requireEmpty(message.target(), "Target ved QUIT");
                requireEmpty(message.payload(), "Payload ved QUIT");
            }
            default -> throw new ProtocolException("Typen " + message.type() + " må kun sendes af serveren.");
        }
    }

    private static void validateServer(ServerMessage message) {
        if (message == null || message.type() == null) {
            throw new ProtocolException("Serverbeskeden og dens type skal være angivet.");
        }
        if (message.timestamp() == null || message.timestamp().getYear() < 1
                || message.timestamp().getYear() > 9999) {
            throw new ProtocolException("Tidspunktet skal have et år fra 0001 til 9999.");
        }
        requireField(message.sender(), "Afsender");
        requireText(message.sender(), "Afsender");
        requireField(message.target(), "Target");
        requireSingleLine(message.payload(), "Payload");
        requireText(message.payload(), "Payload");

        switch (message.type()) {
            case LOGIN_OK, ROOM_JOINED, INFO -> {
                requireServerSender(message.sender());
                requireText(message.target(), "Target");
            }
            case ERROR, BYE -> {
                requireServerSender(message.sender());
                // Før login findes der endnu ikke et brugernavn som target.
                if (!message.target().isEmpty()) {
                    requireText(message.target(), "Target");
                }
            }
            case TEXT, PRIVATE -> requireText(message.target(), "Target");
            default -> throw new ProtocolException("Typen " + message.type() + " må kun sendes af klienten.");
        }
    }

    private static MessageType parseType(String value) {
        try {
            return MessageType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Ukendt beskedtype: " + value, e);
        }
    }

    private static LocalDateTime parseTimestamp(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")) {
            throw new ProtocolException("Tidspunktet skal have formatet yyyy-MM-dd HH:mm:ss.");
        }
        try {
            return LocalDateTime.parse(value, TIMESTAMP_FORMAT);
        } catch (DateTimeException e) {
            throw new ProtocolException("Tidspunktet er ikke en gyldig dato og tid.", e);
        }
    }

    private static void requireField(String value, String name) {
        requireSingleLine(value, name);
        if (value.indexOf('|') >= 0) {
            throw new ProtocolException(name + " må ikke indeholde |.");
        }
    }

    private static void requireSingleLine(String value, String name) {
        if (value == null) {
            throw new ProtocolException(name + " må ikke være null.");
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\u0085') >= 0 || value.indexOf('\u2028') >= 0
                || value.indexOf('\u2029') >= 0) {
            throw new ProtocolException(name + " må ikke indeholde linjeskift.");
        }
    }

    private static void requireText(String value, String name) {
        if (value.isBlank()) {
            throw new ProtocolException(name + " må ikke være tom eller kun indeholde mellemrum.");
        }
    }

    private static void requireEmpty(String value, String name) {
        if (!value.isEmpty()) {
            throw new ProtocolException(name + " skal være tom.");
        }
    }

    private static void requireServerSender(String sender) {
        if (!sender.equals("server")) {
            throw new ProtocolException("Denne svartype skal have server som afsender.");
        }
    }
}
