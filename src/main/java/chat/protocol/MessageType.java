package chat.protocol;

/** Alle kommandotyper og svartyper i chatprotokollen. */
public enum MessageType {
    LOGIN,
    JOIN_ROOM,
    TEXT,
    PRIVATE,
    QUIT,
    LOGIN_OK,
    ROOM_JOINED,
    INFO,
    ERROR,
    BYE
}
