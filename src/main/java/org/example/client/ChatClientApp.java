package org.example.client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.example.client.controller.LoginController;
import org.example.client.service.ClientService;
import org.example.client.ui.ThemeManager;

/**
 * Точка входа клиентской части мессенджера ICQ (JavaFX).
 *
 * <p>Создаёт единый {@link ClientService} (фасад «бэкенда») и показывает экран
 * входа из FXML. Контроллеры получают сервис через {@code init(...)} — UI не
 * знает про сеть. На ПР3 наполним сервис сокетами, и этот класс не изменится.
 */
public class ChatClientApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        // Единый сервис на всё приложение — мост к сети/эхо.
        ClientService service = new ClientService();

        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/org/example/client/view/LoginView.fxml"));
        Parent root = loader.load();

        LoginController controller = loader.getController();
        controller.init(stage, service);

        Scene scene = new Scene(root, 380, 320);
        ThemeManager.getInstance().apply(scene);

        stage.setTitle("ICQ Messenger");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
