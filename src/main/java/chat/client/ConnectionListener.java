package chat.client;

import chat.protocol.ServerMessage;

/** Hændelser fra netværket. Modtageren vælger selv sin UI-tråd. */
public interface ConnectionListener {
    default void onConnected() { }
    default void onMessage(ServerMessage message) { }
    default void onError(String message) { }
    default void onDisconnected(String reason, boolean unexpected) { }
}
