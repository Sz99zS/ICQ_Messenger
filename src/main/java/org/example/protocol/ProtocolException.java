package org.example.protocol;

/**
 * Ошибка кодирования/разбора сообщения протокола (некорректный формат,
 * неизвестный тип и т.п.).
 */
public class ProtocolException extends Exception {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
