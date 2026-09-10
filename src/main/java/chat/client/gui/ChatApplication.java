package chat.client.gui;

import chat.client.ChatConnection;
import chat.client.ConnectionListener;
import chat.protocol.ChatProtocol;
import chat.protocol.ClientMessage;
import chat.protocol.MessageParser;
import chat.protocol.MessageType;
import chat.protocol.ProtocolException;
import chat.protocol.ServerMessage;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** JavaFX-visningerne deler al socketkode med konsolklienten. */
public final class ChatApplication extends Application {
    private enum Pending { NONE, CONNECT, LOGIN, JOIN, TEXT, PRIVATE }

    private final BorderPane root = new BorderPane();
    private final ObservableList<ServerMessage> messages = FXCollections.observableArrayList();
    private final TextField hostField = field("host-field", "Serveradresse", "localhost");
    private final TextField portField = field("port-field", "Port", "5555");
    private final TextField usernameField = field("username-field", "Brugernavn", "Fx alice");
    private final TextField publicField = field("public-message-field", "Besked til rummet", "Skriv en besked …");
    private final TextField recipientField = field("private-recipient-field", "Privat modtager", "Brugernavn");
    private final TextField privateField = field("private-message-field", "Privat besked", "Skriv privat …");
    private final Button connectButton = button("connect-button", "Forbind", "primary-button");
    private final Button cancelLoginButton = button("cancel-login-button", "Afbryd", "secondary-button");
    private final Button publicButton = button("send-message-button", "Send", "primary-button");
    private final Button privateButton = button("send-private-button", "Send privat", "secondary-button");
    private final Button disconnectButton = button("disconnect-button", "Afbryd", "secondary-button");
    private final Button reconnectButton = button("reconnect-button", "Forbind igen", "primary-button");
    private final ComboBox<String> roomSelector = new ComboBox<>(FXCollections.observableArrayList(ChatProtocol.ROOMS));
    private final ListView<ServerMessage> messageList = new ListView<>(messages);
    private final Label loginFeedback = feedback("login-feedback");
    private final Label chatFeedback = feedback("chat-feedback");
    private final Label userLabel = new Label();
    private final Label statusLabel = new Label("Ikke forbundet");
    private final Label roomDescription = new Label();
    private final Label publicLabel = new Label("Besked til rummet");

    private Stage stage;
    private Node loginView;
    private Node chatView;
    private ChatConnection connection;
    private Pending pending = Pending.NONE;
    private String username;
    private String currentRoom;
    private String sentText;
    private String lastNetworkError;
    private boolean connected;
    private boolean loggedIn;
    private boolean closing;
    private boolean chatVisible;
    private boolean updatingRoom;
    private boolean stopping;
    private long generation;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        hostField.setText(getParameters().getNamed().getOrDefault("host", ChatProtocol.DEFAULT_HOST));
        portField.setText(getParameters().getNamed().getOrDefault("port", String.valueOf(ChatProtocol.DEFAULT_PORT)));
        loginView = buildLogin();
        chatView = buildChat();
        showLogin();

        Scene scene = new Scene(root, 640, 540);
        scene.getStylesheets().add(ChatApplication.class.getResource("chat.css").toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(640);
        stage.setMinHeight(540);
        stage.setOnCloseRequest(event -> closeConnection());
        stage.show();
        usernameField.requestFocus();
    }

    private Node buildLogin() {
        Label title = new Label("Mini Chat");
        title.getStyleClass().add("login-title");
        Label description = new Label("Vælg et navn, og mød de andre i lobby.");
        description.getStyleClass().add("muted");
        description.setWrapText(true);
        VBox heading = new VBox(6, title, description);

        VBox host = labeled("Serveradresse", hostField);
        VBox port = labeled("Port", portField);
        HBox.setHgrow(host, Priority.ALWAYS);
        port.setPrefWidth(108);
        port.setMaxWidth(108);
        HBox address = new HBox(12, host, port);
        Label nameHint = new Label("1–20 tegn: a-z, 0-9 og _. Navnet skal være ledigt.");
        nameHint.getStyleClass().add("hint");
        nameHint.setWrapText(true);
        VBox name = labeled("Dit brugernavn", usernameField);
        name.getChildren().add(nameHint);

        connectButton.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(connectButton, Priority.ALWAYS);
        connectButton.setDefaultButton(true);
        connectButton.setOnAction(event -> submitLogin());
        usernameField.setOnAction(event -> submitLogin());
        cancelLoginButton.setOnAction(event -> disconnect());
        HBox actions = new HBox(10, connectButton, cancelLoginButton);

        VBox form = new VBox(18, heading, name, address, loginFeedback, actions);
        form.getStyleClass().add("login-card");
        form.setMaxWidth(470);
        form.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane screen = new StackPane(form);
        screen.setPadding(new Insets(24));
        screen.getStyleClass().add("login-screen");
        return screen;
    }

    private Node buildChat() {
        Label title = new Label("Mini Chat");
        title.getStyleClass().add("chat-title");
        userLabel.setId("current-user");
        userLabel.getStyleClass().add("muted");
        VBox identity = new VBox(3, title, userLabel);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        statusLabel.setId("connection-status");
        statusLabel.getStyleClass().add("status");
        disconnectButton.setOnAction(event -> disconnect());
        reconnectButton.setOnAction(event -> showLogin());
        HBox header = new HBox(12, identity, spacer, statusLabel, disconnectButton, reconnectButton);
        header.setAlignment(Pos.CENTER_LEFT);

        roomSelector.setId("room-selector");
        roomSelector.setAccessibleText("Vælg chatrum");
        roomSelector.setPrefWidth(145);
        roomSelector.setOnAction(event -> changeRoom());
        Label roomLabel = new Label("Rum");
        roomLabel.setLabelFor(roomSelector);
        roomLabel.getStyleClass().add("field-label");
        roomDescription.getStyleClass().add("muted");
        roomDescription.setWrapText(true);
        HBox rooms = new HBox(12, roomLabel, roomSelector, roomDescription);
        rooms.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(20, header, rooms);
        top.setPadding(new Insets(0, 0, 18, 0));

        messageList.setId("message-list");
        messageList.setAccessibleText("Beskeder i chatten");
        messageList.setCellFactory(list -> new MessageCell(list));
        Label emptyTitle = new Label("Her begynder samtalen");
        emptyTitle.getStyleClass().add("empty-title");
        Label emptyHint = new Label("Skriv en besked til de andre i rummet.");
        emptyHint.getStyleClass().add("muted");
        VBox empty = new VBox(6, emptyTitle, emptyHint);
        empty.setAlignment(Pos.CENTER);
        messageList.setPlaceholder(empty);

        publicLabel.getStyleClass().add("field-label");
        publicLabel.setLabelFor(publicField);
        publicButton.setPrefWidth(105);
        publicButton.setOnAction(event -> sendPublic());
        publicField.setOnAction(event -> sendPublic());
        HBox.setHgrow(publicField, Priority.ALWAYS);
        HBox publicRow = new HBox(10, publicField, publicButton);

        recipientField.setPrefWidth(145);
        recipientField.setMaxWidth(145);
        privateButton.setPrefWidth(105);
        privateButton.setOnAction(event -> sendPrivate());
        privateField.setOnAction(event -> sendPrivate());
        HBox.setHgrow(privateField, Priority.ALWAYS);
        HBox privateRow = new HBox(10, recipientField, privateField, privateButton);
        Label privateLabel = new Label("Privat besked · kun til modtageren");
        privateLabel.getStyleClass().add("field-label");
        privateLabel.setLabelFor(privateField);

        VBox compose = new VBox(8, chatFeedback, publicLabel, publicRow,
                new Separator(), privateLabel, privateRow);
        compose.setPadding(new Insets(10, 0, 0, 0));
        BorderPane screen = new BorderPane(messageList, top, null, compose, null);
        screen.setPadding(new Insets(24));
        screen.getStyleClass().add("chat-screen");
        return screen;
    }

    private void submitLogin() {
        if (pending != Pending.NONE || closing) return;
        String requestedName = usernameField.getText();
        if (requestedName.isBlank()) {
            showFeedback("Skriv et brugernavn først.", true);
            usernameField.requestFocus();
            return;
        }
        if (connected) {
            sendCommand(new ClientMessage(MessageType.LOGIN, "", requestedName), Pending.LOGIN);
            return;
        }
        String host = hostField.getText().strip();
        int port;
        try {
            port = Integer.parseInt(portField.getText().strip());
            if (host.isEmpty() || port < 1 || port > 65_535) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            showFeedback("Angiv en serveradresse og en port mellem 1 og 65535.", true);
            return;
        }
        if (connection != null) connection.close();
        long token = ++generation;
        messages.clear();
        lastNetworkError = null;
        pending = Pending.CONNECT;
        connection = new ChatConnection(listener(token, requestedName));
        showFeedback("Forbinder til " + host + ":" + port + " …", false);
        refreshControls();
        connection.connect(host, port);
    }

    private ConnectionListener listener(long token, String requestedName) {
        return new ConnectionListener() {
            @Override
            public void onConnected() {
                onFx(token, () -> {
                    if (closing) return;
                    connected = true;
                    pending = Pending.NONE;
                    sendCommand(new ClientMessage(MessageType.LOGIN, "", requestedName), Pending.LOGIN);
                });
            }

            @Override
            public void onMessage(ServerMessage message) {
                onFx(token, () -> receive(message));
            }

            @Override
            public void onError(String message) {
                onFx(token, () -> {
                    if (closing) return;
                    lastNetworkError = message;
                    pending = Pending.NONE;
                    restoreRoomSelection();
                    showFeedback(message, true);
                    refreshControls();
                });
            }

            @Override
            public void onDisconnected(String reason, boolean unexpected) {
                onFx(token, () -> {
                    // Lukning er terminal: sene callbacks må ikke genåbne denne forbindelse.
                    generation++;
                    connected = false;
                    loggedIn = false;
                    closing = false;
                    pending = Pending.NONE;
                    restoreRoomSelection();
                    String description = unexpected && lastNetworkError != null ? lastNetworkError : reason;
                    showFeedback(description + (unexpected ? " Du kan forbinde igen." : ""), unexpected);
                    refreshControls();
                });
            }
        };
    }

    private void receive(ServerMessage message) {
        // Registrering og broadcast kan ske før LOGIN_OK. Listen bevares ved visningsskift.
        if (chatVisible || message.type() != MessageType.ERROR) {
            messages.add(message);
            messageList.scrollTo(messages.size() - 1);
        }
        if (closing) return;
        switch (message.type()) {
            case LOGIN_OK -> {
                username = message.payload();
                usernameField.setText(username);
                currentRoom = message.target();
                loggedIn = true;
                pending = Pending.NONE;
                showChat();
                showFeedback("Du er klar til at chatte.", false);
            }
            case ROOM_JOINED -> {
                currentRoom = message.target();
                pending = Pending.NONE;
                showFeedback(message.payload(), false);
            }
            case TEXT -> {
                if (pending == Pending.TEXT && message.sender().equals(username)) {
                    clearIfUnchanged(publicField);
                    pending = Pending.NONE;
                    showFeedback("Beskeden er sendt til #" + message.target() + ".", false);
                }
            }
            case INFO -> {
                if (pending == Pending.PRIVATE) {
                    clearIfUnchanged(privateField);
                    pending = Pending.NONE;
                }
                showFeedback(message.payload(), false);
            }
            case ERROR -> {
                pending = Pending.NONE;
                showFeedback(message.payload(), true);
            }
            default -> { /* PRIVATE og BYE vises i listen; forbindelsen giver besked om lukning. */ }
        }
        restoreRoomSelection();
        refreshControls();
    }

    private void changeRoom() {
        if (updatingRoom || !canSend()) return;
        String requestedRoom = roomSelector.getValue();
        if (requestedRoom == null || requestedRoom.equals(currentRoom)) return;
        // Værdien viser det bekræftede rum, indtil ROOM_JOINED kommer tilbage.
        restoreRoomSelection();
        sendCommand(new ClientMessage(MessageType.JOIN_ROOM, requestedRoom, ""), Pending.JOIN);
    }

    private void sendPublic() {
        if (canSend()) {
            sendCommand(new ClientMessage(MessageType.TEXT, currentRoom, publicField.getText()), Pending.TEXT);
        }
    }

    private void sendPrivate() {
        if (canSend()) {
            sendCommand(new ClientMessage(MessageType.PRIVATE, recipientField.getText().strip(),
                    privateField.getText()), Pending.PRIVATE);
        }
    }

    private void sendCommand(ClientMessage message, Pending operation) {
        try {
            MessageParser.format(message);
        } catch (ProtocolException exception) {
            showFeedback(exception.getMessage(), true);
            refreshControls();
            return;
        }
        pending = operation;
        sentText = message.payload();
        String waiting = switch (operation) {
            case LOGIN -> "Logger ind …";
            case JOIN -> "Skifter til #" + message.target() + " …";
            default -> "Sender besked …";
        };
        showFeedback(waiting, false);
        refreshControls();
        long token = generation;
        connection.send(message).whenComplete((ignored, failure) -> {
            if (failure != null) onFx(token, () -> {
                if (closing || !connected) return;
                pending = Pending.NONE;
                showFeedback("Kommandoen kunne ikke sendes. " + failure.getMessage(), true);
                restoreRoomSelection();
                refreshControls();
            });
        });
    }

    private void clearIfUnchanged(TextField field) {
        if (field.getText().equals(sentText)) field.clear();
    }

    private boolean canSend() {
        return connected && loggedIn && !closing && pending == Pending.NONE;
    }

    private void disconnect() {
        if (connection == null || connection.isClosed() || closing) return;
        closing = true;
        showFeedback("Afbryder forbindelsen …", false);
        refreshControls();
        connection.quit();
    }

    private void showLogin() {
        chatVisible = false;
        root.setCenter(loginView);
        stage.setTitle("Mini Chat · Log ind");
        showFeedback("Forbind til en kørende chatserver for at begynde.", false);
        refreshControls();
        usernameField.requestFocus();
    }

    private void showChat() {
        chatVisible = true;
        root.setCenter(chatView);
        stage.setTitle("Mini Chat · " + username);
        stage.setWidth(Math.max(stage.getWidth(), 820));
        stage.setHeight(Math.max(stage.getHeight(), 660));
        userLabel.setText("Logget ind som " + username);
        refreshControls();
        publicField.requestFocus();
    }

    private void restoreRoomSelection() {
        updatingRoom = true;
        roomSelector.setValue(currentRoom);
        updatingRoom = false;
        publicLabel.setText(currentRoom == null ? "Besked til rummet" : "Besked til #" + currentRoom);
        roomDescription.setText(currentRoom == null ? "" : "Alle i #" + currentRoom + " kan læse med.");
    }

    private void refreshControls() {
        boolean busy = pending != Pending.NONE || closing;
        connectButton.setDisable(busy);
        connectButton.setText(pending == Pending.CONNECT ? "Forbinder …"
                : pending == Pending.LOGIN ? "Logger ind …" : connected ? "Log ind" : "Forbind");
        usernameField.setDisable(busy);
        hostField.setDisable(connected || busy);
        portField.setDisable(connected || busy);
        visible(cancelLoginButton, connection != null && !connection.isClosed());
        cancelLoginButton.setDisable(closing);
        publicButton.setDisable(!canSend());
        privateButton.setDisable(!canSend());
        roomSelector.setDisable(!canSend());
        boolean cannotCompose = !connected || !loggedIn || closing;
        publicField.setDisable(cannotCompose);
        privateField.setDisable(cannotCompose);
        recipientField.setDisable(cannotCompose);
        visible(disconnectButton, connected || closing);
        disconnectButton.setDisable(closing);
        visible(reconnectButton, !connected && !closing);
        statusLabel.setText(closing ? "Afbryder …" : connected ? "Online" : "Offline");
        statusLabel.getStyleClass().setAll("status", connected && !closing ? "online" : "offline");
    }

    private void showFeedback(String text, boolean error) {
        Label label = chatVisible ? chatFeedback : loginFeedback;
        label.setText(text);
        label.getStyleClass().setAll("feedback", error ? "error-feedback" : "muted");
    }

    private void onFx(long token, Runnable update) {
        // Gamle forbindelsers callbacks må ikke ændre en ny loginrunde.
        Platform.runLater(() -> {
            if (!stopping && token == generation) update.run();
        });
    }

    private void closeConnection() {
        stopping = true;
        generation++;
        if (connection != null) connection.close();
    }

    @Override
    public void stop() {
        closeConnection();
    }

    private static TextField field(String id, String accessibleText, String prompt) {
        TextField field = new TextField();
        field.setId(id);
        field.setAccessibleText(accessibleText);
        field.setPromptText(prompt);
        return field;
    }

    private static Button button(String id, String text, String styleClass) {
        Button button = new Button(text);
        button.setId(id);
        button.setAccessibleText(text);
        button.getStyleClass().add(styleClass);
        return button;
    }

    private static VBox labeled(String text, Node field) {
        Label label = new Label(text);
        label.setLabelFor(field);
        label.getStyleClass().add("field-label");
        return new VBox(6, label, field);
    }

    private static Label feedback(String id) {
        Label label = new Label();
        label.setId(id);
        label.setWrapText(true);
        label.setMinHeight(22);
        label.setAccessibleText("Status og fejlbeskeder");
        return label;
    }

    private static void visible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
