package org.example.client.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import org.example.client.model.ChatMessage;
import org.example.client.model.Contact;
import org.example.client.model.Status;
import org.example.client.service.ClientService;
import org.example.client.service.ClientServiceListener;
import org.example.client.ui.ThemeManager;
import org.example.protocol.Message;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Контроллер главного окна чата.
 *
 * <p>Реализует {@link ClientServiceListener}: всё, что приходит «снизу» от
 * сервиса, попадает сюда и отображается. Обновления UI обёрнуты в
 * {@code Platform.runLater}, потому что на ПР3 сервис будет звать эти методы
 * из сетевого потока — и тогда менять контроллер уже не понадобится.
 */
public class MainChatController implements ClientServiceListener {

    @FXML private ListView<Contact> contactList;
    @FXML private ListView<ChatMessage> messageList;
    @FXML private TextField inputField;
    @FXML private Label titleLabel;

    private ClientService service;
    private String nick;

    private final ObservableList<ChatMessage> messages = FXCollections.observableArrayList();
    private final ObservableList<Contact> contacts = FXCollections.observableArrayList();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Внедрение зависимостей после загрузки FXML. */
    public void init(ClientService service, String nick) {
        this.service = service;
        this.nick = nick;
        this.service.setListener(this);

        titleLabel.setText("Вы вошли как: " + nick);

        messageList.setItems(messages);
        messageList.setCellFactory(lv -> new ChatBubbleCell());

        contactList.setItems(contacts);
        contactList.setCellFactory(lv -> new ContactCell());
        // Список наполнится с сервера событием USER_LIST (onUserListChanged).
    }

    @FXML
    private void onSend() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        // Своё сообщение сразу в ленту (справа).
        messages.add(new ChatMessage(nick, text, LocalTime.now().format(TIME_FMT), true));
        messageList.scrollTo(messages.size() - 1);

        service.sendMessage(Message.BROADCAST, text);
        inputField.clear();
    }

    @FXML
    private void onToggleTheme() {
        ThemeManager.getInstance().toggle(titleLabel.getScene());
    }

    // ---- ClientServiceListener: события «снизу» ----

    @Override
    public void onConnected() {
        Platform.runLater(() -> titleLabel.setText("Вы вошли как: " + nick + " (онлайн)"));
    }

    @Override
    public void onMessage(Message message) {
        Platform.runLater(() -> {
            messages.add(new ChatMessage(
                    message.getFrom(),
                    message.getBody(),
                    LocalTime.now().format(TIME_FMT),
                    false));
            messageList.scrollTo(messages.size() - 1);
        });
    }

    @Override
    public void onUserListChanged(List<String> nicks) {
        Platform.runLater(() -> {
            contacts.clear();
            for (String n : nicks) {
                // Себя в списке контактов не показываем.
                if (!n.equals(nick)) {
                    contacts.add(new Contact(n, Status.ONLINE));
                }
            }
        });
    }

    @Override
    public void onDisconnected() {
        Platform.runLater(() -> titleLabel.setText("Соединение разорвано"));
    }

    @Override
    public void onError(String reason) {
        Platform.runLater(() -> titleLabel.setText("Ошибка: " + reason));
    }

    /** Ячейка контакта: ник + цветной кружок статуса. */
    private static class ContactCell extends ListCell<Contact> {
        @Override
        protected void updateItem(Contact item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                getStyleClass().removeAll("status-online", "status-away", "status-offline");
                return;
            }
            setText(item.getNick());
            getStyleClass().removeAll("status-online", "status-away", "status-offline");
            getStyleClass().add(switch (item.getStatus()) {
                case ONLINE -> "status-online";
                case AWAY -> "status-away";
                case OFFLINE -> "status-offline";
            });
        }
    }
}
