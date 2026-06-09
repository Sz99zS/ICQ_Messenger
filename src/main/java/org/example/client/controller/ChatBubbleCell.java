package org.example.client.controller;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.example.client.model.ChatMessage;
import org.example.client.ui.Avatars;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Кастомная ячейка ленты сообщений — рисует «пузырь» чата.
 *
 * <p>Свои сообщения прижимаются вправо, чужие — влево; внешний вид задаётся
 * CSS-классами {@code .bubble-mine} / {@code .bubble-other}.
 *
 * <p>Редизайн UI: <ul>
 *   <li><b>аватары</b> — у входящих рядом с пузырём цветной кружок с инициалом;</li>
 *   <li><b>группировка</b> — подряд идущие сообщения одного отправителя в один
 *       день рисуются плотно: имя и аватар только у первого, у остальных —
 *       отступ-распорка;</li>
 *   <li><b>разделители по датам</b> — перед первым сообщением каждого дня
 *       центрированная плашка «Сегодня / Вчера / 5 июня».</li>
 * </ul>
 * Соседа (предыдущее сообщение) ячейка берёт прямо из {@code ListView} по
 * индексу — отдельная модель-разделитель не нужна.
 *
 * <p>ПР15: пузырь может нести файл. Картинка показывается миниатюрой (клик —
 * сохранить), прочее — карточкой с кнопкой «Сохранить».
 */
public class ChatBubbleCell extends ListCell<ChatMessage> {

    /** Максимальная ширина миниатюры картинки в пузыре. */
    private static final double IMAGE_MAX = 220;
    /** Диаметр аватара рядом с входящим пузырём. */
    private static final double AVATAR = 30;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("d MMMM", new Locale("ru"));

    private final MainChatController controller;

    public ChatBubbleCell(MainChatController controller) {
        this.controller = controller;
    }

    @Override
    protected void updateItem(ChatMessage item, boolean empty) {
        super.updateItem(item, empty);

        if (empty || item == null) {
            setText(null);
            setGraphic(null);
            return;
        }

        ChatMessage prev = previousItem();
        boolean newDay = prev == null || !sameDay(prev, item);
        boolean grouped = prev != null && !newDay && sameSender(prev, item);

        // --- пузырь + мета ---
        Region content = item.isFile() ? buildFileContent(item) : buildTextBubble(item);
        content.getStyleClass().add(item.isMine() ? "bubble-mine" : "bubble-other");

        String metaText = item.getTime();
        if (item.isMine() && item.isPrivate() && item.getStatus() != null) {
            metaText += " " + item.getStatus().glyph();
        }
        Label meta = new Label(metaText);
        meta.getStyleClass().add("bubble-meta");

        VBox column = new VBox(2);
        column.setAlignment(item.isMine() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        // Имя отправителя — только у первого сообщения группы входящих.
        if (!item.isMine() && !grouped) {
            Label sender = new Label(item.getSender());
            sender.getStyleClass().add("bubble-sender");
            sender.setTextFill(Avatars.colorFor(item.getSender()));
            column.getChildren().add(sender);
        }
        column.getChildren().addAll(content, meta);

        // --- строка: [аватар|распорка] пузырь (для входящих); свои — справа без аватара ---
        HBox row = new HBox(8);
        row.setMaxWidth(Double.MAX_VALUE);
        if (item.isMine()) {
            row.setAlignment(Pos.CENTER_RIGHT);
            row.getChildren().add(column);
        } else {
            row.setAlignment(Pos.TOP_LEFT);
            row.getChildren().add(grouped
                    ? Avatars.spacer(AVATAR)
                    : Avatars.circle(item.getSender(), AVATAR));
            row.getChildren().add(column);
        }

        // --- обёртка с опциональным разделителем даты ---
        VBox outer = new VBox();
        outer.setMaxWidth(Double.MAX_VALUE);
        if (newDay) {
            outer.getChildren().add(dateSeparator(item));
        }
        outer.getChildren().add(row);
        outer.setPadding(new Insets(grouped ? 1 : 5, 8, 1, 8));

        setGraphic(outer);

        FadeTransition fade = new FadeTransition(Duration.millis(160), outer);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }

    /** Предыдущее сообщение в ленте (для группировки/дат) либо {@code null}. */
    private ChatMessage previousItem() {
        if (getListView() == null) {
            return null;
        }
        List<ChatMessage> items = getListView().getItems();
        int i = getIndex();
        return (i > 0 && i <= items.size() - 1) ? items.get(i - 1) : null;
    }

    /** Центрированная плашка с днём: «Сегодня / Вчера / 5 июня». */
    private Node dateSeparator(ChatMessage item) {
        Label date = new Label(dayLabel(item.getTimestamp()));
        date.getStyleClass().add("date-separator");
        HBox box = new HBox(date);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(4, 0, 6, 0));
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private static String dayLabel(long epochMillis) {
        LocalDate day = dayOf(epochMillis);
        LocalDate today = LocalDate.now();
        if (day.equals(today)) {
            return "Сегодня";
        }
        if (day.equals(today.minusDays(1))) {
            return "Вчера";
        }
        return day.format(DATE_FMT);
    }

    private static boolean sameDay(ChatMessage a, ChatMessage b) {
        return dayOf(a.getTimestamp()).equals(dayOf(b.getTimestamp()));
    }

    private static LocalDate dayOf(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /** Подряд ли идут сообщения одного автора (своё↔своё либо тот же чужой ник). */
    private static boolean sameSender(ChatMessage a, ChatMessage b) {
        if (a.isMine() != b.isMine()) {
            return false;
        }
        return a.isMine() || Objects.equals(a.getSender(), b.getSender());
    }

    /** Обычный текстовый пузырь. */
    private Region buildTextBubble(ChatMessage item) {
        Label bubble = new Label(item.getText());
        bubble.setWrapText(true);
        bubble.setMaxWidth(260);
        return bubble;
    }

    /** Содержимое файлового пузыря: миниатюра картинки либо карточка с «Сохранить». */
    private Region buildFileContent(ChatMessage item) {
        if (item.isImage()) {
            Image image = loadImage(item);
            if (image != null) {
                ImageView view = new ImageView(image);
                view.setPreserveRatio(true);
                view.setFitWidth(Math.min(IMAGE_MAX, image.getWidth()));
                view.setOnMouseClicked(e -> controller.saveFile(item));
                VBox holder = new VBox(view);
                holder.setOnMouseClicked(e -> controller.saveFile(item));
                return holder;
            }
            // байтов пока нет — просим докачать, показываем заглушку
            controller.ensureImageLoaded(item);
            return labelBox("🖼 " + item.getFileName() + "  (загрузка…)");
        }
        // не картинка — карточка с кнопкой «Сохранить»
        Label info = new Label("📄 " + item.getFileName() + "\n" + humanSize(item.getFileSize()));
        Button save = new Button("Сохранить");
        save.getStyleClass().add("ghost-button");
        save.setOnAction(e -> controller.saveFile(item));
        HBox card = new HBox(8, info, save);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(260);
        return card;
    }

    private Image loadImage(ChatMessage item) {
        try {
            if (item.getFileBytes() != null) {
                return new Image(new ByteArrayInputStream(item.getFileBytes()));
            }
            if (item.getLocalFile() != null) {
                return new Image(item.getLocalFile().toURI().toString());
            }
        } catch (RuntimeException ignored) {
            // битый/неподдерживаемый формат — покажем как карточку через заглушку
        }
        return null;
    }

    private static Region labelBox(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(260);
        return new VBox(label);
    }

    /** Человекочитаемый размер: Б / КБ / МБ. */
    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " Б";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f КБ", bytes / 1024.0);
        }
        return String.format("%.1f МБ", bytes / (1024.0 * 1024));
    }
}
