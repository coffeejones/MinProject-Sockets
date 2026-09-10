package chat.protocol;

/** En kommando fra klienten. Serveren tilføjer selv afsender og tidspunkt. */
public record ClientMessage(MessageType type, String target, String payload) {
}
