package org.example.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * Единица обмена в протоколе ICQ — то, чем оперируют ВСЕ слои приложения
 * (GUI, сервис, сеть), независимо от формата на проводе.
 *
 * <p>Это ключевой элемент архитектуры: интерфейс приложения работает с
 * объектами {@code Message}, а не со строками или XML. Конкретный формат
 * (XML на ПР4) прячется за {@link ProtocolCodec}, поэтому замена формата не
 * затрагивает GUI и бизнес-логику.
 *
 * <p>Поле {@link #getAttributes()} даёт расширяемость: можно добавлять новые
 * атрибуты сообщения, не меняя структуру класса и формат протокола.
 */
public final class Message {

    private final MessageType type;
    private final String from;
    private final String to;
    private final String body;
    private final long timestamp;
    private final Map<String, String> attributes;

    /** Псевдо-адресат «всем» для широковещательных сообщений. */
    public static final String BROADCAST = "*";

    public Message(MessageType type, String from, String to, String body, long timestamp) {
        this.type = type;
        this.from = from;
        this.to = to;
        this.body = body;
        this.timestamp = timestamp;
        this.attributes = new HashMap<>();
    }

    /** Удобная фабрика обычного сообщения с текущим временем. */
    public static Message text(String from, String to, String body) {
        return new Message(MessageType.MESSAGE, from, to, body, System.currentTimeMillis());
    }

    /** Удобная фабрика запроса на вход. */
    public static Message login(String nick) {
        return new Message(MessageType.LOGIN, nick, null, null, System.currentTimeMillis());
    }

    public MessageType getType()           { return type; }
    public String getFrom()                { return from; }
    public String getTo()                  { return to; }
    public String getBody()                { return body; }
    public long getTimestamp()             { return timestamp; }
    public Map<String, String> getAttributes() { return attributes; }

    /** Широковещательное ли это сообщение (адресат не указан). */
    public boolean isBroadcast() {
        return to == null || to.isEmpty() || BROADCAST.equals(to);
    }

    @Override
    public String toString() {
        return "Message{" + type + " from=" + from + " to=" + to + " body=" + body + "}";
    }
}
