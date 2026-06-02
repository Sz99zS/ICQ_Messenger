package org.example.client;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Точка входа клиентской части мессенджера ICQ (JavaFX).
 *
 * <p>Пока это заглушка: показывает пустое окно. В следующих лабораторных
 * здесь появится полноценный GUI, подключение к серверу через сокет и
 * обмен сообщениями по XML-протоколу из пакета {@code org.example.protocol}.
 */
public class ChatClientApp extends Application {

    @Override
    public void start(Stage stage) {
        Label label = new Label("ICQ — клиент запущен");
        Scene scene = new Scene(new StackPane(label), 360, 240);

        stage.setTitle("ICQ Messenger");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
