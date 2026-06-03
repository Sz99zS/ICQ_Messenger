package org.example.client.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.example.client.service.ClientService;

import java.io.IOException;

/**
 * Контроллер экрана входа.
 *
 * <p>Собирает адрес сервера и ник, просит {@link ClientService} подключиться,
 * и при успехе переключает сцену на главное окно чата. Сам про сеть ничего не
 * знает — только вызывает фасад.
 */
public class LoginController {

    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private TextField nickField;
    @FXML private Label statusLabel;

    private Stage stage;
    private ClientService service;

    /** Внедрение зависимостей после загрузки FXML. */
    public void init(Stage stage, ClientService service) {
        this.stage = stage;
        this.service = service;
    }

    @FXML
    private void onConnect() {
        String host = hostField.getText().trim();
        String nick = nickField.getText().trim();
        String portText = portField.getText().trim();

        if (nick.isEmpty()) {
            statusLabel.setText("Введите ник");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            statusLabel.setText("Порт должен быть числом");
            return;
        }

        // Подключаемся ДО открытия чата: при ошибке остаёмся на экране входа.
        try {
            service.connect(host, port, nick);
        } catch (IOException e) {
            statusLabel.setText("Не удалось подключиться: " + e.getMessage());
            return;
        }
        openChat(nick);
    }

    /** Загружает главное окно, выставляет слушателя и запускает чтение. */
    private void openChat(String nick) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/org/example/client/view/MainChatView.fxml"));
            Parent root = loader.load();
            MainChatController controller = loader.getController();
            controller.init(service, nick); // здесь сервис получает слушателя

            // Переиспользуем сцену — тема (CSS) сохраняется.
            stage.getScene().setRoot(root);
            stage.setTitle("ICQ Messenger — " + nick);

            // Слушатель готов — можно начинать читать сообщения с сервера.
            service.start();
        } catch (Exception e) {
            statusLabel.setText("Не удалось открыть чат: " + e.getMessage());
        }
    }
}
