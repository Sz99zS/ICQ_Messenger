package org.example.client.controller;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.example.client.model.ChatMessage;

/**
 * Кастомная ячейка ленты сообщений — рисует «пузырь» чата.
 *
 * <p>Свои сообщения прижимаются вправо, чужие — влево; внешний вид (цвет,
 * скругление) задаётся CSS-классами {@code .bubble-mine} / {@code .bubble-other}.
 * При появлении ячейки проигрывается лёгкая анимация затухания — это часть
 * «вайба», заложенного на ПР2.
 */
public class ChatBubbleCell extends ListCell<ChatMessage> {

    @Override
    protected void updateItem(ChatMessage item, boolean empty) {
        super.updateItem(item, empty);

        if (empty || item == null) {
            setText(null);
            setGraphic(null);
            return;
        }

        Label bubble = new Label(item.getText());
        bubble.setWrapText(true);
        bubble.setMaxWidth(260);
        bubble.getStyleClass().add(item.isMine() ? "bubble-mine" : "bubble-other");

        Label meta = new Label((item.isMine() ? "" : item.getSender() + " · ") + item.getTime());
        meta.getStyleClass().add("bubble-meta");

        VBox box = new VBox(2, bubble, meta);
        box.setPadding(new Insets(4, 8, 4, 8));
        box.setAlignment(item.isMine() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        box.setMaxWidth(Double.MAX_VALUE);

        setGraphic(box);

        FadeTransition fade = new FadeTransition(Duration.millis(180), box);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }
}
