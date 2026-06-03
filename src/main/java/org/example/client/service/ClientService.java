package org.example.client.service;

import org.example.protocol.Message;

/**
 * Фасад клиента — единственная точка, через которую GUI работает с «бэкендом».
 *
 * <p>Это и есть «база под сеть», заложенная заранее: контроллеры зовут
 * {@link #connect}, {@link #login}, {@link #sendMessage}, а откуда берутся
 * данные — локальное эхо (ПР2) или реальный сокет (ПР3) — им не важно.
 * На ПР3 здесь появятся {@code Socket} и поток-читатель, а сигнатуры методов
 * и контракт со слушателем НЕ изменятся, поэтому GUI переписывать не придётся.
 *
 * <p>Текущая реализация (ПР2) — заглушка с локальным «эхо-ботом»: всё, что
 * отправляет пользователь, возвращается ответом. Это позволяет полностью
 * проверить интерфейс (пузыри, темы, прокрутку) ещё до появления сервера.
 */
public class ClientService {

    private ClientServiceListener listener;
    private String nick;
    private boolean connected;

    public void setListener(ClientServiceListener listener) {
        this.listener = listener;
    }

    /**
     * Подключение к серверу. На ПР2 — мгновенно «успешно» (без сети).
     * На ПР3 здесь будет {@code new Socket(host, port)} и запуск чтения.
     */
    public void connect(String host, int port, String nick) {
        this.nick = nick;
        this.connected = true;
        if (listener != null) {
            listener.onConnected();
        }
    }

    /** Запрос на вход под ником (на ПР2 совмещён с connect). */
    public void login(String nick) {
        this.nick = nick;
    }

    /**
     * Отправка сообщения. На ПР2 — локальное эхо обратно в ленту,
     * чтобы было видно входящие пузыри. На ПР3 — отправка в сокет.
     */
    public void sendMessage(String to, String text) {
        if (!connected || listener == null) {
            return;
        }
        // ЭХО (только ПР2): сервер появится на ПР3 и заменит этот блок.
        Message echo = Message.text("echo-бот", nick, "Эхо: " + text);
        listener.onMessage(echo);
    }

    /** Отключение от сервера. */
    public void disconnect() {
        connected = false;
        if (listener != null) {
            listener.onDisconnected();
        }
    }

    public boolean isConnected() { return connected; }
    public String getNick()      { return nick; }
}
