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

    /** Время последнего полученного PONG (мс) — для детекта «смерти» сервера. */
    private volatile long lastPong;

    /** Период отправки PING (мс). Сервер реапит при отсутствии кадров ~15с. */
    private static final long PING_INTERVAL_MS = 5_000;

    /** Нет PONG дольше этого времени → считаем сервер недоступным (≈3 пинга). */
    private static final long PONG_TIMEOUT_MS = 15_000;

    public void setListener(ClientServiceListener listener) {
        this.listener = listener;
    }

    /**
     * Вход по паролю (ПР12). Открывает соединение, шлёт {@code LOGIN} и
     * <b>блокирующе</b> ждёт вердикт сервера. Так неуспех (неверный пароль)
     * оставляет пользователя на экране входа, а не бросает в чат.
     *
     * <p>Поток-читатель ещё не запущен (см. {@link #start}): хендшейк читает
     * первый кадр сам, а последующие ({@code USER_LIST}, история) буферизуются
     * тем же {@link Connection} и не теряются. При неуспехе соединение закрывается.
     *
     * @throws IOException если не удалось подключиться к серверу
     */
    public AuthResult login(String host, int port, String nick, String password)
            throws IOException {
        openConnection(host, port);
        connection.send(Message.login(nick, password));
        return awaitLogin(nick);
    }

    /**
     * Регистрация новой учётки (ПР12) с последующим автоматическим входом по
     * тому же соединению. При неуспехе регистрации вход не выполняется.
     *
     * @throws IOException если не удалось подключиться к серверу
     */
    public AuthResult register(String host, int port, String nick, String password)
            throws IOException {
        openConnection(host, port);
        connection.send(Message.register(nick, password));
        Message resp = connection.receive();
        if (resp == null || resp.getType() != MessageType.REGISTER_OK) {
            return failAndClose(resp, "Регистрация отклонена");
        }
        // Учётка создана — сразу входим по тому же соединению.
        connection.send(Message.login(nick, password));
        return awaitLogin(nick);
    }

    /** Открывает (пере)соединение к серверу, закрыв предыдущее, если оно было. */
    private void openConnection(String host, int port) throws IOException {
        closeQuietly();
        this.socket = new Socket(host, port);
        this.connection = new Connection(socket, ProtocolFactory.createCodec());
    }

    /** Блокирующе ждёт ответ на {@code LOGIN} и трактует его. */
    private AuthResult awaitLogin(String nick) throws IOException {
        Message resp = connection.receive();
        if (resp != null && resp.getType() == MessageType.LOGIN_OK) {
            this.nick = nick;
            return AuthResult.success();
        }
        return failAndClose(resp, "Вход отклонён");
    }

    /** Закрывает соединение и формирует отказ с текстом из ответа сервера. */
    private AuthResult failAndClose(Message resp, String fallback) {
        String reason = resp == null ? "Сервер закрыл соединение" : resp.getBody();
        closeQuietly();
        return AuthResult.failure(reason != null && !reason.isBlank() ? reason : fallback);
    }

    /** Тихо закрывает текущее соединение/сокет (между попытками входа). */
    private void closeQuietly() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
        socket = null;
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
        lastPong = System.currentTimeMillis(); // стартовый кредит доверия
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "icq-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(this::heartbeatTick,
                PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Тик heartbeat: сперва проверяем, отвечал ли сервер (приходили ли PONG), а
     * затем шлём очередной PING.
     *
     * <p>Двусторонняя проверка: молчаливо «умерший» сервер не всегда роняет
     * запись PING (данные уходят в буфер ОС), поэтому полагаемся на отсутствие
     * PONG — так разрыв ловится быстро и надёжно, а не по TCP-таймауту ОС.
     */
    private void heartbeatTick() {
        if (connection == null || !running) {
            return;
        }
        if (System.currentTimeMillis() - lastPong > PONG_TIMEOUT_MS) {
            notifyError("Сервер не отвечает");
            disconnect();
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
            case DELIVERED -> listener.onDelivered(msg.getAttributes().get("id"));
            case READ -> listener.onRead(msg.getFrom());
            case USER_LIST -> listener.onUserListChanged(parsePresence(msg.getBody()));
            case TYPING -> listener.onTyping(msg.getFrom(), "1".equals(msg.getBody()));
            case USER_JOINED, USER_LEFT -> { /* список придёт отдельным USER_LIST */ }
            case PONG -> lastPong = System.currentTimeMillis(); // сервер жив
            case ERROR -> notifyError(msg.getBody());
            default -> { /* прочие служебные типы игнорируем */ }
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

    /**
     * Отправка сообщения на сервер. {@code id} (ПР14) — клиентский идентификатор
     * для сопоставления с квитанциями доставки; сервер его сохраняет как есть.
     */
    public void sendMessage(String to, String text, String id) {
        if (connection == null || !running) {
            return;
        }
        try {
            Message msg = new Message(MessageType.MESSAGE, nick, to, text,
                    System.currentTimeMillis());
            if (id != null) {
                msg.getAttributes().put("id", id);
            }
            connection.send(msg);
        } catch (IOException e) {
            notifyError("Не удалось отправить: " + e.getMessage());
        }
    }

    /**
     * Сообщает серверу, что мы открыли диалог с {@code peer} и прочли его
     * сообщения (ПР14). Сервер пометит их и уведомит отправителя (✓✓). Ошибки
     * глушим — квитанция некритична.
     */
    public void sendRead(String peer) {
        if (connection == null || !running || peer == null) {
            return;
        }
        try {
            connection.send(new Message(MessageType.READ, nick, peer, null,
                    System.currentTimeMillis()));
        } catch (IOException ignored) {
            // намеренно тихо
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
