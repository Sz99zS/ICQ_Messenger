package org.example.client.service;

import org.example.protocol.Message;

import java.util.List;

/**
 * Слушатель событий сервиса — мост от сети к GUI (паттерн «Наблюдатель»).
 *
 * <p>Контроллер главного окна реализует этот интерфейс. Сервис вызывает его
 * методы, когда что-то приходит «снизу» (с сервера). Благодаря этому GUI не
 * знает про сокеты, а сетевой код — про JavaFX.
 *
 * <p>ВАЖНО: на ПР3 эти методы вызываются из сетевого потока, поэтому любое
 * обновление UI в реализации нужно оборачивать в {@code Platform.runLater}.
 */
public interface ClientServiceListener {

    /** Соединение с сервером установлено и вход выполнен. */
    void onConnected();

    /** Пришло текстовое сообщение от другого пользователя. */
    void onMessage(Message message);

    /** Обновился список онлайн-пользователей. */
    void onUserListChanged(List<String> nicks);

    /** Соединение разорвано. */
    void onDisconnected();

    /** Произошла ошибка (текст для показа пользователю). */
    void onError(String reason);
}
