package org.example.client.service;

import org.example.client.model.Status;
import org.example.client.model.UserPresence;
import org.example.net.Connection;
import org.example.protocol.Message;
import org.example.protocol.MessageType;
import org.example.protocol.ProtocolFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    /** Фоновый heartbeat: шлёт PING, чтобы сервер видел живость соединения. */
    private ScheduledExecutorService heartbeat;

    /** Период отправки PING (мс). Сервер реапит при отсутствии кадров ~15с. */
    private static final long PING_INTERVAL_MS = 5_000;

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

    /** Запускает фоновое чтение сообщений с сервера и heartbeat. */
    public void start() {
        running = true;
        readerThread = new Thread(this::readLoop, "icq-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        startHeartbeat();
    }

    /** Поднимает планировщик, который шлёт PING каждые {@link #PING_INTERVAL_MS}. */
    private void startHeartbeat() {
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "icq-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(this::sendPing,
                PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Отправляет служебный PING. Если запись упала — сервер недоступен: сообщаем
     * об ошибке и рвём соединение (так клиент узнаёт о «смерти» сервера быстрее,
     * чем по зависшему чтению).
     */
    private void sendPing() {
        if (connection == null || !running) {
            return;
        }
        try {
            connection.send(new Message(MessageType.PING, nick, null, null,
                    System.currentTimeMillis()));
        } catch (IOException e) {
            notifyError("Сервер не отвечает");
            disconnect();
        }
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
            case USER_LIST -> listener.onUserListChanged(parsePresence(msg.getBody()));
            case TYPING -> listener.onTyping(msg.getFrom(), "1".equals(msg.getBody()));
            case USER_JOINED, USER_LEFT -> { /* список придёт отдельным USER_LIST */ }
            case ERROR -> notifyError(msg.getBody());
            default -> { /* PING — на будущее */ }
        }
    }

    /** Разбирает тело USER_LIST формата {@code ник:СТАТУС,...} в список присутствий. */
    private List<UserPresence> parsePresence(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        List<UserPresence> users = new ArrayList<>();
        for (String token : body.split(",")) {
            int sep = token.lastIndexOf(':');
            String nick = sep < 0 ? token : token.substring(0, sep);
            if (nick.isBlank()) {
                continue;
            }
            Status status = Status.ONLINE;
            if (sep >= 0) {
                try {
                    status = Status.valueOf(token.substring(sep + 1));
                } catch (IllegalArgumentException ignored) {
                    // неизвестный статус — считаем онлайн
                }
            }
            users.add(new UserPresence(nick, status));
        }
        return users;
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

    /**
     * Сообщает серверу, что пользователь печатает (или перестал). Доставляется
     * всем собеседникам. Ошибки глушим — индикатор «печатает…» некритичен.
     */
    public void sendTyping(boolean typing) {
        if (connection == null || !running) {
            return;
        }
        try {
            connection.send(new Message(MessageType.TYPING, nick, Message.BROADCAST,
                    typing ? "1" : "0", System.currentTimeMillis()));
        } catch (IOException ignored) {
            // намеренно тихо
        }
    }

    /** Закрывает соединение, останавливает чтение и heartbeat. */
    public void disconnect() {
        running = false;
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
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
