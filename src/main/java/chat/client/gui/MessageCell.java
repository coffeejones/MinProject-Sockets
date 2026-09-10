package chat.client.gui;

import chat.protocol.MessageType;
import chat.protocol.ServerMessage;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;

/** En genbrugt celle viser altid tid, afsender og beskedens eget rum eller modtager. */
final class MessageCell extends ListCell<ServerMessage> {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Label metadata = new Label();
    private final Label body = new Label();
    private final VBox card = new VBox(5, metadata, body);

    MessageCell(ListView<ServerMessage> list) {
        metadata.getStyleClass().add("message-meta");
        metadata.setWrapText(true);
        body.getStyleClass().add("message-body");
        body.setWrapText(true);
        card.prefWidthProperty().bind(list.widthProperty().subtract(48));
    }

    @Override
    protected void updateItem(ServerMessage message, boolean empty) {
        super.updateItem(message, empty);
        setText(null);
        if (empty || message == null) {
            setGraphic(null);
            setAccessibleText(null);
            return;
        }
        String time = TIME.format(message.timestamp());
        card.getStyleClass().setAll("message-card");
        switch (message.type()) {
            case TEXT -> metadata.setText(time + "  ·  " + message.sender() + "  ·  #" + message.target());
            case PRIVATE -> {
                metadata.setText(time + "  ·  Privat fra " + message.sender() + " til " + message.target());
                card.getStyleClass().add("private-message");
            }
            default -> {
                metadata.setText(time + "  ·  Server");
                card.getStyleClass().add(message.type() == MessageType.ERROR ? "error-message" : "system-message");
            }
        }
        body.setText(message.type() == MessageType.LOGIN_OK
                ? "Du er logget ind som " + message.payload() + " i #" + message.target() + "."
                : message.payload());
        setGraphic(card);
        setAccessibleText(metadata.getText() + ". " + body.getText());
    }
}
