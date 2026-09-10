package chat.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageParserTest {
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 9, 10, 15, 0, 0);
    private static final String TIME_TEXT = "2026-09-10 15:00:00";

    @ParameterizedTest
    @MethodSource("clientMessages")
    void parsesAndFormatsAllClientTypes(String line, ClientMessage expected) {
        assertEquals(expected, MessageParser.parseClient(line));
        assertEquals(line, MessageParser.format(expected));
    }

    static Stream<Arguments> clientMessages() {
        return Stream.of(
                Arguments.of("LOGIN||bob", new ClientMessage(MessageType.LOGIN, "", "bob")),
                Arguments.of("JOIN_ROOM|java|", new ClientMessage(MessageType.JOIN_ROOM, "java", "")),
                Arguments.of("TEXT|lobby|Hej æøå | verden!",
                        new ClientMessage(MessageType.TEXT, "lobby", "Hej æøå | verden!")),
                Arguments.of("PRIVATE|alice| Hej | Alice | ",
                        new ClientMessage(MessageType.PRIVATE, "alice", " Hej | Alice | ")),
                Arguments.of("QUIT||", new ClientMessage(MessageType.QUIT, "", "")));
    }

    @ParameterizedTest
    @MethodSource("serverMessages")
    void parsesAndFormatsAllServerTypes(MessageType type, String sender, String target, String payload) {
        ServerMessage expected = new ServerMessage(TIME, type, sender, target, payload);
        String line = TIME_TEXT + "|" + type + "|" + sender + "|" + target + "|" + payload;

        assertEquals(expected, MessageParser.parseServer(line));
        assertEquals(line, MessageParser.format(expected));
    }

    static Stream<Arguments> serverMessages() {
        return Stream.of(
                Arguments.of(MessageType.LOGIN_OK, "server", "lobby", "bob"),
                Arguments.of(MessageType.ROOM_JOINED, "server", "java", "Du er nu i java."),
                Arguments.of(MessageType.TEXT, "bob", "java", "Hej æøå | verden!"),
                Arguments.of(MessageType.PRIVATE, "bob", "alice", " Hemmelig | tekst | "),
                Arguments.of(MessageType.INFO, "server", "bob", "Beskeden er sendt."),
                Arguments.of(MessageType.ERROR, "server", "bob", "Rummet findes ikke."),
                Arguments.of(MessageType.ERROR, "server", "", "Brugernavnet er optaget."),
                Arguments.of(MessageType.BYE, "server", "bob", "Farvel."),
                Arguments.of(MessageType.BYE, "server", "", "Farvel."));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", "LOGIN", "LOGIN|", "QUIT|", "TEXT|lobby", "UNKNOWN||bob", "login||bob",
            "LOGIN|lobby|bob", "LOGIN||", "LOGIN||  ", "JOIN_ROOM||", "JOIN_ROOM| |",
            "JOIN_ROOM|java|tekst", "JOIN_ROOM|java||", "TEXT||hej", "TEXT| |hej", "TEXT|lobby|",
            "TEXT|lobby|  \t ", "PRIVATE||hej", "PRIVATE|alice|", "QUIT|bob|", "QUIT||tekst",
            "LOGIN_OK||bob", "ROOM_JOINED|java|ok", "INFO|bob|hej", "ERROR||fejl", "BYE||farvel",
            "TEXT|lobby|hej\nQUIT||", "TEXT|lobby|hej\rQUIT||", "TEXT|lobby|hej\u2028verden"
    })
    void rejectsMalformedClientLines(String line) {
        assertThrows(ProtocolException.class, () -> MessageParser.parseClient(line));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", "TEXT|bob|lobby|hej", "2026-09-10 15:00:00|TEXT|bob|lobby",
            "2026-09-10 15:00:00|UNKNOWN|server|bob|hej",
            "2026-09-10 15:00:00|LOGIN|server||bob",
            "2026-09-10 15:00:00|JOIN_ROOM|server|java|ok",
            "2026-09-10 15:00:00|QUIT|server||farvel",
            "2026-09-10 15:00:00|TEXT||lobby|hej",
            "2026-09-10 15:00:00|TEXT| |lobby|hej",
            "2026-09-10 15:00:00|TEXT|bob||hej",
            "2026-09-10 15:00:00|PRIVATE|bob|alice|",
            "2026-09-10 15:00:00|TEXT|bob|lobby|  \t ",
            "2026-09-10 15:00:00|LOGIN_OK|bob|lobby|bob",
            "2026-09-10 15:00:00|ROOM_JOINED|server||ok",
            "2026-09-10 15:00:00|INFO|server||sendt",
            "2026-09-10 15:00:00|ERROR|server| |fejl",
            "2026-09-10 15:00:00|BYE|server||",
            "2026-09-10 15:00:00|TEXT|bob|lobby|hej\nny linje"
    })
    void rejectsMalformedServerLines(String line) {
        assertThrows(ProtocolException.class, () -> MessageParser.parseServer(line));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-02-30 15:00:00", "2026-13-10 15:00:00", "2026-09-10 24:00:00",
            "2026-09-10 15:60:00", "2026-09-10 15:00:60", "2026-9-10 15:00:00",
            "2026-09-10T15:00:00", "2026-09-10 15:00:00.123", "0000-09-10 15:00:00",
            "2026-09-10 15:00:00Z", "+2026-09-10 15:00:00"
    })
    void rejectsInvalidTimestamps(String timestamp) {
        assertThrows(ProtocolException.class,
                () -> MessageParser.parseServer(timestamp + "|TEXT|bob|lobby|hej"));
    }

    @Test
    void acceptsRealLeapDayAndFormatsAtSecondPrecision() {
        ServerMessage message = new ServerMessage(LocalDateTime.of(2024, 2, 29, 12, 30, 45, 123),
                MessageType.TEXT, "bob", "lobby", "hej");
        String line = MessageParser.format(message);
        assertEquals("2024-02-29 12:30:45|TEXT|bob|lobby|hej", line);
        assertEquals(message.timestamp().withNano(0), MessageParser.parseServer(line).timestamp());
    }

    @Test
    void leavesUsernameAndRoomRulesToServer() {
        assertEquals("Bob!", MessageParser.parseClient("LOGIN||Bob!").payload());
        assertEquals("ukendt", MessageParser.parseClient("JOIN_ROOM|ukendt|").target());
    }

    @ParameterizedTest
    @MethodSource("invalidClientObjects")
    void formattingAlsoValidatesClientObjects(ClientMessage message) {
        assertThrows(ProtocolException.class, () -> MessageParser.format(message));
    }

    static Stream<ClientMessage> invalidClientObjects() {
        return Stream.of(
                null,
                new ClientMessage(null, "", ""),
                new ClientMessage(MessageType.LOGIN, null, "bob"),
                new ClientMessage(MessageType.TEXT, "lobby", null),
                new ClientMessage(MessageType.TEXT, "lobby|hej", "tekst"),
                new ClientMessage(MessageType.TEXT, "lobby\n", "tekst"),
                new ClientMessage(MessageType.TEXT, "lobby", "hej\r\nQUIT||"),
                new ClientMessage(MessageType.ERROR, "", "fejl"),
                new ClientMessage(MessageType.QUIT, "", " "));
    }

    @ParameterizedTest
    @MethodSource("invalidServerObjects")
    void formattingAlsoValidatesServerObjects(ServerMessage message) {
        assertThrows(ProtocolException.class, () -> MessageParser.format(message));
    }

    static Stream<ServerMessage> invalidServerObjects() {
        return Stream.of(
                null,
                new ServerMessage(null, MessageType.TEXT, "bob", "lobby", "hej"),
                new ServerMessage(TIME, null, "bob", "lobby", "hej"),
                new ServerMessage(TIME, MessageType.TEXT, null, "lobby", "hej"),
                new ServerMessage(TIME, MessageType.TEXT, "bob", null, "hej"),
                new ServerMessage(TIME, MessageType.TEXT, "bob", "lobby", null),
                new ServerMessage(TIME, MessageType.TEXT, "bob|alice", "lobby", "hej"),
                new ServerMessage(TIME, MessageType.TEXT, "bob\n", "lobby", "hej"),
                new ServerMessage(TIME, MessageType.TEXT, "bob", "lobby|java", "hej"),
                new ServerMessage(TIME, MessageType.TEXT, "bob", "lobby", "hej\r\nny besked"),
                new ServerMessage(TIME, MessageType.INFO, "bob", "bob", "sendt"),
                new ServerMessage(TIME, MessageType.LOGIN, "server", "", "bob"),
                new ServerMessage(TIME.withYear(10000), MessageType.TEXT, "bob", "lobby", "hej"));
    }
}
