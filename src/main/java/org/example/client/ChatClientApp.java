package org.example.client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import org.example.client.controller.LoginController;
import org.example.client.service.ClientService;
import org.example.client.ui.ThemeManager;

/**
 * Точка входа клиентской части мессенджера ICQ (JavaFX).
 *
 * <p>Создаёт единый {@link ClientService} (фасад «бэкенда») и показывает экран
 * входа из FXML. Контроллеры получают сервис через {@code init(...)} — UI не
 * знает про сеть.
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

        Scene scene = new Scene(root, 400, 480);
        ThemeManager.getInstance().apply(scene);

        stage.setTitle("ICQ Messenger");
        stage.getIcons().add(appIcon());
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Иконка приложения, нарисованная программно (без бинарного ассета): оранжевый
     * скруглённый квадрат с буквой «i» — узнаваемый фирменный значок в духе нового
     * вида клиента.
     */
    private static Image appIcon() {
        double s = 64;
        Canvas canvas = new Canvas(s, s);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#ff8a00"));
        g.fillRoundRect(4, 4, s - 8, s - 8, 20, 20);
        g.setFill(Color.WHITE);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 40));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(javafx.geometry.VPos.CENTER);
        g.fillText("i", s / 2, s / 2 + 2);

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return canvas.snapshot(params, new WritableImage((int) s, (int) s));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
