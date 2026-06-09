package org.example.client.service;

import org.example.client.model.UserPresence;
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

    /** Квитанция «доставлено» (ПР14): наше личное сообщение {@code messageId} дошло. */
    void onDelivered(String messageId);

    /** Квитанция «прочитано» (ПР14): собеседник {@code peer} прочитал нашу с ним переписку. */
    void onRead(String peer);

    /** Файл {@code fileId} полностью скачан (ПР15): его байты — для превью/сохранения. */
    void onFileReceived(String fileId, byte[] bytes);

    /** Обновился список онлайн-пользователей (с их статусами). */
    void onUserListChanged(List<UserPresence> users);

    /** Пришёл список существующих комнат (ПР16) — для диалога «войти в комнату». */
    void onRoomList(List<String> rooms);

    /** Обновился состав комнаты {@code room} (ПР16): {@code members} — её участники. */
    void onRoomMembers(String room, List<String> members);

    /** Сменилось состояние «печатает…» у пользователя {@code nick}. */
    void onTyping(String nick, boolean typing);

    /** Связь потеряна, идёт авто-переподключение (ПР17) — для индикации в UI. */
    void onReconnecting();

    /** Связь восстановлена после обрыва (ПР17). */
    void onReconnected();

    /** Соединение разорвано окончательно (пользователь вышел или исчерпаны попытки). */
    void onDisconnected();

    /** Произошла ошибка (текст для показа пользователю). */
    void onError(String reason);
}
