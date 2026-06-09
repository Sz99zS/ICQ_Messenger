package org.example.client.service;

import org.example.client.model.Status;
import org.example.client.model.UserPresence;
import org.example.net.Connection;
import org.example.protocol.Message;
import org.example.protocol.MessageType;
import org.example.protocol.ProtocolFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // --- авто-реконнект (ПР17) ---
    /** Параметры последнего успешного входа — чтобы переподключиться тем же. */
    private String host;
    private int port;
    private String password;
    /** Пользователь сам закрыл соединение → переподключаться не нужно. */
    private volatile boolean userClosed;
    /** Идёт ли сейчас цикл переподключения (чтобы не запускать второй). */
    private final java.util.concurrent.atomic.AtomicBoolean reconnecting =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Стартовая пауза между попытками переподключения (мс). */
    private static final long RECONNECT_BASE_MS = 1_000;
    /** Потолок паузы между попытками (мс) — экспоненциальный backoff упирается в него. */
    private static final long RECONNECT_MAX_MS = 5_000;
    /** Максимум попыток переподключения, после чего сдаёмся окончательно. */
    private static final int RECONNECT_MAX_ATTEMPTS = 30;

    /** Размер чанка при загрузке файла (сырые байты). */
    private static final int FILE_CHUNK_BYTES = 48 * 1024;
    /** Идущие сейчас скачивания: ref(fileId) → накопитель байтов (живёт в потоке-читателе). */
    private final Map<String, ByteArrayOutputStream> downloads = new HashMap<>();

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
        AuthResult result = awaitLogin(nick);
        if (result.ok()) {
            rememberCredentials(host, port, password);
        }
        return result;
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
        AuthResult result = awaitLogin(nick);
        if (result.ok()) {
            rememberCredentials(host, port, password);
        }
        return result;
    }

    /** Сохраняет параметры входа для будущих авто-переподключений (ПР17). */
    private void rememberCredentials(String host, int port, String password) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.userClosed = false;
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
        userClosed = false;
        startReader();
        startHeartbeat();
    }

    /** Поднимает поток-читатель на текущем соединении. */
    private void startReader() {
        running = true;
        readerThread = new Thread(this::readLoop, "icq-reader");
        readerThread.setDaemon(true);
        readerThread.start();
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

    /** Останавливает планировщик heartbeat (между переподключениями и при закрытии). */
    private void stopHeartbeat() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
            heartbeat = null;
        }
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
            loseConnection(); // нет PONG → роняем соединение, дальше попробуем переподключиться
            return;
        }
        try {
            connection.send(new Message(MessageType.PING, nick, null, null,
                    System.currentTimeMillis()));
        } catch (IOException e) {
            loseConnection();
        }
    }

    /**
     * Фиксирует потерю соединения (ПР17): останавливает чтение и закрывает сокет.
     * Закрытие разблокирует {@link #readLoop()}, чей {@code finally} решит, что
     * делать дальше — переподключаться или завершиться (см. {@link #userClosed}).
     */
    private void loseConnection() {
        if (!running) {
            return; // уже теряли — не плодим повторов
        }
        running = false;
        if (connection != null) {
            connection.close();
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
            // Разрыв — это нормальный путь к переподключению, шум в UI не нужен.
        } finally {
            running = false;
            stopHeartbeat();
            if (userClosed) {
                notifyDisconnected(); // пользователь сам вышел — это финал
            } else {
                reconnect(); // обрыв сети/сервера — пробуем восстановить
            }
        }
    }

    /**
     * Запускает (один) фоновый цикл переподключения (ПР17). Идемпотентен:
     * повторные вызовы при гонке heartbeat/reader игнорируются.
     */
    private void reconnect() {
        if (userClosed || !reconnecting.compareAndSet(false, true)) {
            return;
        }
        if (listener != null) {
            listener.onReconnecting();
        }
        Thread t = new Thread(this::reconnectLoop, "icq-reconnect");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Цикл восстановления связи с экспоненциальным backoff. Повторяет вход тем же
     * ником/паролем, пока не получится. {@code LOGIN_FAIL} здесь почти всегда
     * означает, что сервер ещё не «сжал» старую сессию («уже в сети») — это
     * временно, поэтому тоже повторяем. После {@link #RECONNECT_MAX_ATTEMPTS}
     * безуспешных попыток сдаёмся окончательно.
     */
    private void reconnectLoop() {
        long delay = RECONNECT_BASE_MS;
        for (int attempt = 1; attempt <= RECONNECT_MAX_ATTEMPTS && !userClosed; attempt++) {
            sleep(delay);
            if (userClosed) {
                break;
            }
            try {
                openConnection(host, port);
                connection.send(Message.login(nick, password));
                Message resp = connection.receive();
                if (resp != null && resp.getType() == MessageType.LOGIN_OK) {
                    reconnecting.set(false);
                    startReader();   // буферизованные за LOGIN_OK кадры (история/списки) подхватит он
                    startHeartbeat();
                    if (listener != null) {
                        listener.onReconnected();
                    }
                    return;
                }
                // LOGIN_FAIL (вероятно «уже в сети», пока не отработал реапер) — повторим.
                closeQuietly();
            } catch (IOException e) {
                // Сервер ещё недоступен — следующая попытка.
            }
            delay = Math.min(delay * 2, RECONNECT_MAX_MS);
        }
        // Цикл закончился. Если это не пользовательское закрытие — значит исчерпали
        // попытки: честно сообщаем о финальном обрыве.
        reconnecting.set(false);
        if (!userClosed) {
            notifyError("Не удалось переподключиться к серверу");
            notifyDisconnected();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void notifyDisconnected() {
        if (listener != null) {
            listener.onDisconnected();
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
            case FILE_START -> downloads.put(msg.getAttributes().get("ref"), new ByteArrayOutputStream());
            case FILE_CHUNK -> appendDownload(msg);
            case FILE_END -> finishDownload(msg);
            case USER_LIST -> listener.onUserListChanged(parsePresence(msg.getBody()));
            case ROOM_LIST -> listener.onRoomList(parseCsv(msg.getBody()));
            case ROOM_MEMBERS -> listener.onRoomMembers(msg.getTo(), parseCsv(msg.getBody()));
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

    /** Разбирает список ников/комнат, разделённых запятыми (пустой → пустой список). */
    private static List<String> parseCsv(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String token : body.split(",")) {
            if (!token.isBlank()) {
                items.add(token);
            }
        }
        return items;
    }

    /** Войти в комнату {@code room} (создаётся на сервере, если её ещё нет) — ПР16. */
    public void joinRoom(String room) {
        sendRoomControl(MessageType.ROOM_JOIN, room);
    }

    /** Покинуть комнату {@code room} (ПР16). */
    public void leaveRoom(String room) {
        sendRoomControl(MessageType.ROOM_LEAVE, room);
    }

    /** Запросить у сервера список существующих комнат (ПР16). */
    public void requestRoomList() {
        sendRoomControl(MessageType.ROOM_LIST, null);
    }

    private void sendRoomControl(MessageType type, String room) {
        if (connection == null || !running) {
            return;
        }
        try {
            connection.send(new Message(type, nick, room, null, System.currentTimeMillis()));
        } catch (IOException e) {
            notifyError("Не удалось выполнить операцию с комнатой: " + e.getMessage());
        }
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

    /**
     * Загружает файл на сервер (ПР15) чанками в фоновом потоке, чтобы не морозить
     * UI. {@code ref} — клиентский хэндл: он же станет id сообщения-ссылки (для
     * галочек). По завершении сервер сам разошлёт сообщение-ссылку адресату.
     */
    public void uploadFile(String to, File file, String ref) {
        if (connection == null || !running || file == null) {
            return;
        }
        Thread t = new Thread(() -> doUpload(to, file, ref), "icq-upload");
        t.setDaemon(true);
        t.start();
    }

    private void doUpload(String to, File file, String ref) {
        try {
            Message start = new Message(MessageType.FILE_START, nick, to, null,
                    System.currentTimeMillis());
            start.getAttributes().put("ref", ref);
            start.getAttributes().put("name", file.getName());
            start.getAttributes().put("size", Long.toString(file.length()));
            String mime = probeMime(file);
            if (mime != null) {
                start.getAttributes().put("mime", mime);
            }
            connection.send(start);

            byte[] buf = new byte[FILE_CHUNK_BYTES];
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
                int read;
                while ((read = in.read(buf)) > 0) {
                    byte[] data = read == buf.length ? buf : java.util.Arrays.copyOf(buf, read);
                    Message chunk = new Message(MessageType.FILE_CHUNK, nick, to,
                            Base64.getEncoder().encodeToString(data), System.currentTimeMillis());
                    chunk.getAttributes().put("ref", ref);
                    connection.send(chunk);
                }
            }
            Message end = new Message(MessageType.FILE_END, nick, to, null,
                    System.currentTimeMillis());
            end.getAttributes().put("ref", ref);
            connection.send(end);
        } catch (IOException e) {
            notifyError("Не удалось отправить файл: " + e.getMessage());
        }
    }

    /** Запрашивает у сервера скачивание файла {@code fileId} (ПР15). */
    public void requestFile(String fileId) {
        if (connection == null || !running || fileId == null) {
            return;
        }
        try {
            Message get = new Message(MessageType.FILE_GET, nick, null, null,
                    System.currentTimeMillis());
            get.getAttributes().put("fileId", fileId);
            connection.send(get);
        } catch (IOException e) {
            notifyError("Не удалось запросить файл: " + e.getMessage());
        }
    }

    private static String probeMime(File file) {
        try {
            return Files.probeContentType(file.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    private void appendDownload(Message msg) {
        ByteArrayOutputStream buf = downloads.get(msg.getAttributes().get("ref"));
        if (buf != null && msg.getBody() != null) {
            byte[] data = Base64.getDecoder().decode(msg.getBody());
            buf.write(data, 0, data.length);
        }
    }

    private void finishDownload(Message msg) {
        String ref = msg.getAttributes().get("ref");
        ByteArrayOutputStream buf = downloads.remove(ref);
        if (buf != null && listener != null) {
            listener.onFileReceived(ref, buf.toByteArray());
        }
    }

    /**
     * Пользовательское закрытие соединения: останавливает чтение, heartbeat и
     * <b>отменяет</b> авто-переподключение (ПР17) — в отличие от сетевого обрыва.
     */
    public void disconnect() {
        userClosed = true;
        running = false;
        stopHeartbeat();
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
