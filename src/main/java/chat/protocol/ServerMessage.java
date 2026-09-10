package chat.protocol;

import java.time.LocalDateTime;

/** Et svar eller en besked med serverens tidspunkt og godkendte afsender. */
public record ServerMessage(
        LocalDateTime timestamp,
        MessageType type,
        String sender,
        String target,
        String payload) {
}
