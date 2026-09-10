package chat.protocol;

/** En protokollinje har forkert struktur eller ugyldige felter. */
public class ProtocolException extends IllegalArgumentException {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
