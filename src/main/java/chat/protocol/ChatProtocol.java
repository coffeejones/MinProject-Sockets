package chat.protocol;

import java.util.List;

public final class ChatProtocol {
    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 5555;
    public static final List<String> ROOMS = List.of("lobby", "java", "hygge");
    public static final String START_ROOM = "lobby";

    private ChatProtocol() {
    }
}
