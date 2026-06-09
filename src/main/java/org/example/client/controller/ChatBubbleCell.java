package org.example.client.controller;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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

import java.io.ByteArrayInputStream;

/**
 * Кастомная ячейка ленты сообщений — рисует «пузырь» чата.
 *
 * <p>Свои сообщения прижимаются вправо, чужие — влево; внешний вид задаётся
 * CSS-классами {@code .bubble-mine} / {@code .bubble-other}.
 *
 * <p>ПР15: пузырь может нести файл. Картинка показывается миниатюрой (клик —
 * сохранить), прочее — карточкой с кнопкой «Сохранить». Байты картинки берутся
 * из локального файла (у отправителя) или из скачанных байтов (у получателя);
 * если их ещё нет — просим контроллер докачать и показываем заглушку.
 */
public class ChatBubbleCell extends ListCell<ChatMessage> {

    /** Максимальная ширина миниатюры картинки в пузыре. */
    private static final double IMAGE_MAX = 220;

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

        Region content = item.isFile() ? buildFileContent(item) : buildTextBubble(item);
        content.getStyleClass().add(item.isMine() ? "bubble-mine" : "bubble-other");

        String metaText = (item.isMine() ? "" : item.getSender() + " · ") + item.getTime();
        // ПР14: на «своих» личных пузырях — галочка статуса (⏳/✓/✓✓).
        if (item.isMine() && item.isPrivate() && item.getStatus() != null) {
            metaText += " " + item.getStatus().glyph();
        }
        Label meta = new Label(metaText);
        meta.getStyleClass().add("bubble-meta");

        VBox box = new VBox(2, content, meta);
        box.setPadding(new Insets(4, 8, 4, 8));
        box.setAlignment(item.isMine() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        box.setMaxWidth(Double.MAX_VALUE);

        setGraphic(box);

        FadeTransition fade = new FadeTransition(Duration.millis(180), box);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
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
