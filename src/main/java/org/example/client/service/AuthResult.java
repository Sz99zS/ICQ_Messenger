package org.example.client.service;

/**
 * Результат синхронного хендшейка входа/регистрации (ПР12).
 *
 * <p>Аутентификация выполняется ДО открытия окна чата и блокирующе ждёт ответ
 * сервера. Так неуспех (неверный пароль, занятый ник) оставляет пользователя на
 * экране входа с понятным сообщением, а не бросает его в чат.
 *
 * @param ok      успешен ли вход
 * @param message текст ошибки для показа (при {@code ok == false}); иначе {@code null}
 */
public record AuthResult(boolean ok, String message) {

    public static AuthResult success() {
        return new AuthResult(true, null);
    }

    public static AuthResult failure(String message) {
        return new AuthResult(false, message);
    }
}
