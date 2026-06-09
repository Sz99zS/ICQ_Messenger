package org.example.client.model;

/**
 * UI-модель одного сообщения в ленте чата.
 *
 * <p>Отделена от протокольного {@code org.example.protocol.Message}: здесь
 * хранится только то, что нужно для отрисовки пузыря (текст, отправитель,
 * время и флаг «моё/чужое» для выравнивания влево/вправо).
 *
 * <p>ПР14: у «своих» личных сообщений есть ещё {@link #getId() id} (для сопоставления
 * с квитанциями доставки) и изменяемый {@link #getStatus() статус} ✓/✓✓ — он
 * единственное мутабельное поле, обновляется при приходе квитанции и
 * перерисовывается через {@code ListView.refresh()}.
 */
public final class ChatMessage {

    private final String id;
    private final String sender;
    private final String text;
    private final String time;
    private final boolean mine;
    /** Личное ли (не общий чат) — только у таких показываем галочки статуса. */
    private final boolean privateChat;
    /** Статус доставки; {@code null} для чужих и широковещательных — значка нет. */
    private DeliveryStatus status;

    /** Полный конструктор (ПР14). */
    public ChatMessage(String id, String sender, String text, String time, boolean mine,
                       boolean privateChat, DeliveryStatus status) {
        this.id = id;
        this.sender = sender;
        this.text = text;
        this.time = time;
        this.mine = mine;
        this.privateChat = privateChat;
        this.status = status;
    }

    /** Упрощённый конструктор для сообщений без статуса (чужие, общий чат). */
    public ChatMessage(String sender, String text, String time, boolean mine) {
        this(null, sender, text, time, mine, false, null);
    }

    public String getId()         { return id; }
    public String getSender()     { return sender; }
    public String getText()       { return text; }
    public String getTime()       { return time; }
    public boolean isMine()       { return mine; }
    public boolean isPrivate()    { return privateChat; }
    public DeliveryStatus getStatus() { return status; }

    public void setStatus(DeliveryStatus status) {
        this.status = status;
    }
}
