package org.example.client.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.Locale;

/**
 * Аватары-кружки с инициалами (редизайн UI).
 *
 * <p>Цвет детерминирован по нику — у одного и того же собеседника он всегда
 * одинаковый, но при этом разные ники различимы. Так в общем чате и комнатах
 * видно, кто пишет, без загрузки картинок. Используется и в списке контактов,
 * и рядом с пузырями входящих сообщений.
 */
public final class Avatars {

    private Avatars() { }

    /** Кружок-аватар размера {@code size} с инициалом ника. */
    public static Node circle(String nick, double size) {
        return circle(nick, size, null);
    }

    /**
     * Кружок-аватар с опциональной точкой-статусом в правом нижнем углу.
     *
     * @param statusColor цвет точки присутствия (online/away/offline) либо
     *                    {@code null}, если индикатор не нужен (комнаты, общий чат)
     */
    public static Node circle(String nick, double size, Color statusColor) {
        Circle bg = new Circle(size / 2.0, colorFor(nick));

        Label initial = new Label(initial(nick));
        initial.setTextFill(Color.WHITE);
        initial.setStyle("-fx-font-weight: bold; -fx-font-size: " + Math.round(size * 0.42) + "px;");

        StackPane avatar = new StackPane(bg, initial);
        avatar.setMinSize(size, size);
        avatar.setPrefSize(size, size);
        avatar.setMaxSize(size, size);

        if (statusColor != null) {
            double dot = Math.max(8, size * 0.30);
            Circle ring = new Circle(dot / 2.0 + 1.5, Color.web("#ffffff"));
            Circle status = new Circle(dot / 2.0, statusColor);
            StackPane badge = new StackPane(ring, status);
            badge.setMaxSize(dot + 3, dot + 3);
            StackPane.setAlignment(badge, Pos.BOTTOM_RIGHT);
            avatar.getChildren().add(badge);
        }
        return avatar;
    }

    /** Аватар с произвольным глифом и цветом (для «Общего чата» и подобных). */
    public static Node glyph(String text, Color color, double size) {
        Circle bg = new Circle(size / 2.0, color);
        Label label = new Label(text);
        label.setTextFill(Color.WHITE);
        label.setStyle("-fx-font-weight: bold; -fx-font-size: " + Math.round(size * 0.42) + "px;");
        StackPane avatar = new StackPane(bg, label);
        avatar.setMinSize(size, size);
        avatar.setPrefSize(size, size);
        avatar.setMaxSize(size, size);
        return avatar;
    }

    /** Невидимая «распорка» под аватар — для выравнивания сгруппированных сообщений. */
    public static Node spacer(double size) {
        StackPane s = new StackPane();
        s.setMinSize(size, size);
        s.setPrefSize(size, size);
        s.setMaxSize(size, size);
        return s;
    }

    /** Детерминированный по нику цвет (ровный тон, белый текст читается). */
    public static Color colorFor(String nick) {
        int hue = Math.floorMod(nick == null ? 0 : nick.hashCode(), 360);
        return Color.hsb(hue, 0.62, 0.72);
    }

    /** Инициал для кружка: первая буква ника (для комнат — «#»). */
    private static String initial(String nick) {
        if (nick == null || nick.isBlank()) {
            return "?";
        }
        String s = nick.startsWith("#") ? nick.substring(1) : nick;
        if (s.isBlank()) {
            return "#";
        }
        return s.substring(0, 1).toUpperCase(Locale.ROOT);
    }
}
