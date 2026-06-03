package org.example.client.service;

import org.example.net.Connection;
import org.example.protocol.Message;
import org.example.protocol.MessageType;
import org.example.protocol.ProtocolFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.List;

/**
 * Фасад клиента — единственная точка, через которую GUI работает с сетью.
 *
 * <p>На ПР3 эхо-заглушка заменена реальным {@link Socket}: сервис подключается
 * к серверу, отправляет {@code LOGIN}, а входящие сообщения читает в отдельном
 * потоке-читателе и пробрасывает в GUI через {@link ClientServiceListener}.
 * Сигнатуры методов и контракт со слушателем не изменились — GUI трогать не
 * пришлось.
 *
 * <p>Порядок использования из контроллера входа:
 * {@link #connect} (может бросить {@link IOException}) → выставить слушателя →
 * {@link #start}.
 */
public class ClientService {

    private ClientServiceListener listener;
    private String nick;

    private Socket socket;
    private Connection connection;
    private Thread readerThread;
    private volatile boolean running;

    public void setListener(ClientServiceListener listener) {
        this.listener = listener;
    }

    /**
     * Открывает соединение и отправляет запрос на вход. Поток-читатель ещё не
     * запущен (см. {@link #start}), поэтому ответ сервера буферизуется сокетом
     * и не теряется, пока контроллер выставляет слушателя.
     *
     * @throws IOException если не удалось подключиться к серверу
     */
    public void connect(String host, int port, String nick) throws IOException {
        this.nick = nick;
        this.socket = new Socket(host, port);
        this.connection = new Connection(socket, ProtocolFactory.createCodec());
        connection.send(Message.login(nick));
    }

    /** Запускает фоновое чтение сообщений с сервера. */
    public void start() {
        running = true;
        readerThread = new Thread(this::readLoop, "icq-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Цикл чтения: выполняется в сетевом потоке, не в потоке JavaFX. */
    private void readLoop() {
        try {
            Message msg;
            while (running && (msg = connection.receive()) != null) {
                dispatch(msg);
            }
        } catch (IOException e) {
            if (running) {
                notifyError("Соединение прервано: " + e.getMessage());
            }
        } finally {
            running = false;
            if (listener != null) {
                listener.onDisconnected();
            }
        }
    }

    /** Раскладывает входящее сообщение по событиям слушателя. */
    private void dispatch(Message msg) {
        if (listener == null) {
            return;
        }
        switch (msg.getType()) {
            case LOGIN_OK -> listener.onConnected();
            case LOGIN_FAIL -> {
                notifyError("Вход отклонён: " + msg.getBody());
                disconnect();
            }
            case MESSAGE -> listener.onMessage(msg);
            case USER_LIST -> listener.onUserListChanged(parseNicks(msg.getBody()));
            case USER_JOINED, USER_LEFT -> { /* список придёт отдельным USER_LIST */ }
            case ERROR -> notifyError(msg.getBody());
            default -> { /* TYPING/PING — на ПР5 */ }
        }
    }

    private List<String> parseNicks(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        return List.of(body.split(","));
    }

    /** Отправка сообщения на сервер. */
    public void sendMessage(String to, String text) {
        if (connection == null || !running) {
            return;
        }
        try {
            connection.send(new Message(MessageType.MESSAGE, nick, to, text,
                    System.currentTimeMillis()));
        } catch (IOException e) {
            notifyError("Не удалось отправить: " + e.getMessage());
        }
    }

    /** Закрывает соединение и останавливает чтение. */
    public void disconnect() {
        running = false;
        if (connection != null) {
            connection.close();
        }
    }

    private void notifyError(String reason) {
        if (listener != null) {
            listener.onError(reason);
        }
    }

    public boolean isConnected() { return running; }
    public String getNick()      { return nick; }
}
