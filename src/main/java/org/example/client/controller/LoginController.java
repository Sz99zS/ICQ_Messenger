package org.example.client.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.example.client.service.AuthResult;
import org.example.client.service.ClientService;

import java.io.IOException;

/**
 * Контроллер экрана входа.
 *
 * <p>Собирает адрес сервера, ник и пароль, просит {@link ClientService} войти
 * либо зарегистрироваться, и при успехе переключает сцену на главное окно чата.
 * Сам про сеть ничего не знает — только вызывает фасад.
 */
public class LoginController {

    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private TextField nickField;
    @FXML private PasswordField passwordField;
    @FXML private Label statusLabel;

    private Stage stage;
    private ClientService service;

    /** Внедрение зависимостей после загрузки FXML. */
    public void init(Stage stage, ClientService service) {
        this.stage = stage;
        this.service = service;
    }

    /** Кнопка «Войти»: аутентификация по существующей учётке. */
    @FXML
    private void onConnect() {
        authenticate(service::login);
    }

    /** Кнопка «Регистрация»: создание новой учётки с автоматическим входом. */
    @FXML
    private void onRegister() {
        authenticate(service::register);
    }

    /**
     * Общий путь для входа и регистрации: валидирует поля, выполняет
     * блокирующий хендшейк через переданное действие фасада и при успехе
     * открывает чат. Любой неуспех оставляет пользователя на экране входа.
     */
    private void authenticate(AuthAction action) {
        String host = hostField.getText().trim();
        String nick = nickField.getText().trim();
        String password = passwordField.getText();
        String portText = portField.getText().trim();

        if (nick.isEmpty()) {
            statusLabel.setText("Введите ник");
            return;
        }
        if (password.isEmpty()) {
            statusLabel.setText("Введите пароль");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            statusLabel.setText("Порт должен быть числом");
            return;
        }

        // Хендшейк ДО открытия чата: при ошибке остаёмся на экране входа.
        AuthResult result;
        try {
            result = action.run(host, port, nick, password);
        } catch (IOException e) {
            statusLabel.setText("Не удалось подключиться: " + e.getMessage());
            return;
        }
        if (!result.ok()) {
            statusLabel.setText(result.message());
            return;
        }
        openChat(nick);
    }

    /** Действие фасада (вход/регистрация) с единой сигнатурой хендшейка. */
    @FunctionalInterface
    private interface AuthAction {
        AuthResult run(String host, int port, String nick, String password) throws IOException;
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
