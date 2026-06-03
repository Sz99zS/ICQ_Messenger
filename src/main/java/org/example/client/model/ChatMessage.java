package org.example.client.model;

/**
 * UI-модель одного сообщения в ленте чата.
 *
 * <p>Отделена от протокольного {@code org.example.protocol.Message}: здесь
 * хранится только то, что нужно для отрисовки пузыря (текст, отправитель,
 * время и флаг «моё/чужое» для выравнивания влево/вправо).
 */
public final class ChatMessage {

    private final String sender;
    private final String text;
    private final String time;
    private final boolean mine;

    public ChatMessage(String sender, String text, String time, boolean mine) {
        this.sender = sender;
        this.text = text;
        this.time = time;
        this.mine = mine;
    }

    public String getSender() { return sender; }
    public String getText()   { return text; }
    public String getTime()   { return time; }
    public boolean isMine()   { return mine; }
}
