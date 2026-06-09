package org.example.server;

import org.example.net.Connection;
import org.example.protocol.Message;
import org.example.protocol.MessageType;
import org.example.protocol.ProtocolFactory;
import org.example.server.store.AccountStore;
import org.example.server.store.FileStore;
import org.example.server.store.MessageStore;
import org.example.server.store.OfflineStore;
import org.example.server.store.ReadReceiptStore;
import org.example.server.store.RoomStore;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Обработчик одного клиента — выполняется в отдельном потоке.
 *
 * <p>Жизненный цикл: дождаться {@code LOGIN}, зарегистрировать ник, разослать
 * событие о входе и список онлайн-пользователей, затем в цикле читать сообщения
 * и передавать их {@link MessageRouter}. При разрыве соединения снимает себя с
 * регистрации и оповещает остальных ({@code USER_LEFT}).
 */
public class ClientHandler implements Runnable {

    /** Атрибут-метка: сообщение из истории, а не «живое» (см. {@link #sendHistory()}). */
    public static final String ATTR_HISTORY = "hist";
    /**
     * Атрибут со статусом доставки исходящего личного сообщения (ПР14),
     * проставляется при проигрывании истории отправителю: {@code P} — ждёт
     * доставки (в очереди), {@code D} — доставлено, {@code R} — прочитано.
     */
    public static final String ATTR_STATUS = "st";
    public static final String STATUS_PENDING = "P";
    public static final String STATUS_DELIVERED = "D";
    public static final String STATUS_READ = "R";

    /** Атрибуты сообщения-ссылки на файл (ПР15). */
    public static final String ATTR_FILE = "file";   // "1" — это файл
    public static final String ATTR_FILE_ID = "fileId";
    public static final String ATTR_NAME = "name";
    public static final String ATTR_SIZE = "size";
    public static final String ATTR_MIME = "mime";
    public static final String ATTR_REF = "ref";      // хэндл передачи (FILE_*)

    /** Сколько сырых байт в одном чанке при отдаче файла на скачивание. */
    private static final int DOWNLOAD_CHUNK = 48 * 1024;

    private final Connection connection;
    private final ClientRegistry registry;
    private final MessageRouter router;
    private final MessageStore store;
    private final AccountStore accounts;
    private final OfflineStore offline;
    private final ReadReceiptStore readReceipts;
    private final FileStore files;
    private final RoomStore rooms;
    /** Идущие сейчас загрузки от этого клиента: ref → сессия (поток у нас один). */
    private final Map<String, FileStore.UploadSession> uploads = new HashMap<>();
    private String nick;

    public ClientHandler(Socket socket, ClientRegistry registry, MessageRouter router,
                         MessageStore store, AccountStore accounts, OfflineStore offline,
                         ReadReceiptStore readReceipts, FileStore files, RoomStore rooms)
            throws IOException {
        this.connection = new Connection(socket, ProtocolFactory.createCodec());
        this.registry = registry;
        this.router = router;
        this.store = store;
        this.accounts = accounts;
        this.offline = offline;
        this.readReceipts = readReceipts;
        this.files = files;
        this.rooms = rooms;
    }

    @Override
    public void run() {
        try {
            if (!handleLogin()) {
                connection.close();
                return;
            }
            // Основной цикл приёма сообщений.
            Message msg;
            while ((msg = connection.receive()) != null) {
                if (msg.getType() == MessageType.PING) {
                    // Heartbeat: подтверждает живость, но активностью не считается
                    // (иначе клиент никогда не уходил бы в AWAY) и не маршрутизируется.
                    // В ответ шлём PONG — так клиент видит, что сервер жив.
                    registry.recordHeartbeat(nick);
                    connection.send(new Message(MessageType.PONG, "server", nick, null,
                            System.currentTimeMillis()));
                    continue;
                }
                registry.recordActivity(nick);         // реальное сообщение = активность
                registry.broadcastUserListIfChanged();  // вернулся из AWAY → обновить статус
                // Файловые кадры держат состояние соединения (сборка загрузки),
                // поэтому обрабатываются здесь, а не в маршрутизаторе.
                switch (msg.getType()) {
                    case FILE_START -> handleFileStart(msg);
                    case FILE_CHUNK -> handleFileChunk(msg);
                    case FILE_END -> handleFileEnd(msg);
                    case FILE_GET -> handleFileGet(msg);
                    default -> router.route(msg, this);
                }
            }
        } catch (IOException e) {
            System.out.println("[server] Соединение с " + nick + " прервано: " + e.getMessage());
        } finally {
            disconnect();
        }
    }

    /**
     * Фаза аутентификации (ПР12): принимает кадры до успешного входа.
     * {@code REGISTER} создаёт учётку и оставляет клиента на этой же фазе (нужно
     * ещё войти), {@code LOGIN} проверяет пароль и при успехе открывает сессию.
     * Любой другой тип до входа — нарушение протокола, рвём соединение.
     */
    private boolean handleLogin() throws IOException {
        Message msg;
        while ((msg = connection.receive()) != null) {
            switch (msg.getType()) {
                case REGISTER -> handleRegister(msg);
                case LOGIN -> {
                    if (handleAuthenticatedLogin(msg)) {
                        return true;
                    }
                    // неуспех уже отправлен клиенту — ждём следующую попытку
                }
                default -> {
                    return false; // до входа других кадров быть не должно
                }
            }
        }
        return false; // соединение закрылось, не дойдя до входа
    }

    /** Регистрирует новую учётку и сообщает клиенту результат. Сессию не открывает. */
    private void handleRegister(Message msg) throws IOException {
        String requested = msg.getFrom();
        String error = accounts.register(requested, msg.getBody());
        if (error == null) {
            connection.send(new Message(MessageType.REGISTER_OK, "server", requested,
                    null, System.currentTimeMillis()));
        } else {
            connection.send(new Message(MessageType.REGISTER_FAIL, "server", requested,
                    error, System.currentTimeMillis()));
        }
    }

    /**
     * Проверяет пароль и, при успехе, регистрирует онлайн-присутствие, шлёт
     * историю и анонсирует вход. Возвращает {@code true}, если сессия открыта.
     */
    private boolean handleAuthenticatedLogin(Message msg) throws IOException {
        String requested = msg.getFrom();
        if (requested == null || requested.isBlank()) {
            connection.send(new Message(MessageType.LOGIN_FAIL, "server", null,
                    "Пустой ник", System.currentTimeMillis()));
            return false;
        }
        // Универсальная формулировка: не раскрываем, существует ли ник (анти-перебор).
        if (!accounts.verify(requested, msg.getBody())) {
            connection.send(new Message(MessageType.LOGIN_FAIL, "server", requested,
                    "Неверный ник или пароль", System.currentTimeMillis()));
            return false;
        }
        if (!registry.register(requested, this)) {
            connection.send(new Message(MessageType.LOGIN_FAIL, "server", requested,
                    "Этот пользователь уже в сети", System.currentTimeMillis()));
            return false;
        }
        this.nick = requested;
        System.out.println("[server] Вошёл: " + nick + " (" + connection.getRemoteAddress() + ")");

        connection.send(new Message(MessageType.LOGIN_OK, "server", nick, null,
                System.currentTimeMillis()));
        // Новичку — текущий список онлайн со статусами; остальным — что появился новый.
        connection.send(new Message(MessageType.USER_LIST, "server", nick,
                registry.formatUserList(), System.currentTimeMillis()));
        // История — до анонса о входе остальным, чтобы «живые» сообщения не
        // вклинились в середину проигрываемой ленты.
        sendHistory();
        sendRoomState(); // ПР16: список комнат и состав тех, где состою
        registry.broadcast(new Message(MessageType.USER_JOINED, "server", null, nick,
                System.currentTimeMillis()), nick);
        registry.broadcastUserList();
        return true;
    }

    /**
     * Проигрывает вошедшему его переписку: общий чат плюс личные диалоги с его
     * участием (ПР9).
     *
     * <p>ПР13: сообщения, накопившиеся в очереди оффлайн-доставки, пока он был не
     * в сети, отдаются как «живые» (без метки {@link #ATTR_HISTORY}) — клиент
     * поднимет по ним бейдж «непрочитано». Остальное идёт историей (с меткой),
     * чтобы не плодить ложных непрочитанных. После проигрывания очередь
     * очищается: всё доставлено. Адресат уже зарегистрирован онлайн, поэтому
     * новые сообщения в это время идут напрямую и в очередь не попадают.
     *
     * <p>ПР14: на каждое <em>исходящее</em> личное сообщение вошедшего ставим
     * атрибут {@link #ATTR_STATUS} (ждёт/доставлено/прочитано), чтобы он увидел
     * актуальные галочки на своих пузырях. А каждое сообщение, которое сейчас
     * <em>вынимается из его очереди</em>, переходит в «доставлено» — шлём об этом
     * квитанцию его отправителю (если тот онлайн).
     */
    private void sendHistory() throws IOException {
        Set<String> unread = offline.pendingFor(nick);
        Set<String> myRooms = rooms.roomsOf(nick); // ПР16: подмешиваем историю моих комнат
        // Кэш очередей адресатов: статус исходящих определяем, не дёргая стор на каждое.
        Map<String, Set<String>> pendingByRecipient = new HashMap<>();
        for (Message past : store.historyFor(nick, myRooms)) {
            Message replay = new Message(past.getType(), past.getFrom(), past.getTo(),
                    past.getBody(), past.getTimestamp());
            replay.getAttributes().putAll(past.getAttributes()); // переносим id и пр.
            String id = past.getAttributes().get(MessageStore.ATTR_ID);
            boolean unreadToMe = id != null && unread.contains(id);
            // Недоставленное мне (в очереди) — как «живое»; всё прочее — историей.
            if (!unreadToMe) {
                replay.getAttributes().put(ATTR_HISTORY, "1");
            }
            // Статус для МОИХ исходящих личных пузырей (комнат не касается — там нет галочек).
            if (nick.equals(past.getFrom()) && !past.isBroadcast() && !past.isRoom() && id != null) {
                replay.getAttributes().put(ATTR_STATUS, outgoingStatus(past.getTo(), id, pendingByRecipient));
            }
            connection.send(replay);
            // Сообщение из моей очереди только что доставлено — уведомляем отправителя
            // (только личка: у комнат и общего чата галочек нет).
            if (unreadToMe && !past.isRoom()) {
                notifyDeliveredToSender(past.getFrom(), id);
            }
        }
        offline.clear(nick);
    }

    /**
     * При входе сообщает клиенту картину комнат (ПР16): полный список
     * существующих комнат ({@code ROOM_LIST}) и состав каждой комнаты, где
     * пользователь состоит ({@code ROOM_MEMBERS}) — по ним клиент восстановит
     * свои комнаты в списке слева и заголовок с участниками.
     */
    private void sendRoomState() throws IOException {
        connection.send(new Message(MessageType.ROOM_LIST, "server", null,
                String.join(",", rooms.allRooms()), System.currentTimeMillis()));
        for (String room : rooms.roomsOf(nick)) {
            connection.send(new Message(MessageType.ROOM_MEMBERS, "server", room,
                    String.join(",", rooms.membersOf(room)), System.currentTimeMillis()));
        }
    }

    /** Статус исходящего личного сообщения: ждёт доставки / доставлено / прочитано. */
    private String outgoingStatus(String recipient, String id,
                                  Map<String, Set<String>> pendingByRecipient) {
        Set<String> recipientQueue = pendingByRecipient.computeIfAbsent(
                recipient, offline::pendingFor);
        if (recipientQueue.contains(id)) {
            return STATUS_PENDING;
        }
        return readReceipts.isRead(id) ? STATUS_READ : STATUS_DELIVERED;
    }

    /** Шлёт отправителю {@code sender} квитанцию «доставлено» по сообщению {@code id}. */
    private void notifyDeliveredToSender(String sender, String id) {
        if (sender == null || id == null) {
            return;
        }
        Message receipt = new Message(MessageType.DELIVERED, nick, sender, null,
                System.currentTimeMillis());
        receipt.getAttributes().put(MessageStore.ATTR_ID, id);
        registry.sendTo(sender, receipt);
    }

    // ---- передача файлов (ПР15) ----

    /** Начало загрузки: открываем сессию записи на диск, запоминаем адресата. */
    private void handleFileStart(Message msg) throws IOException {
        String ref = msg.getAttributes().get(ATTR_REF);
        if (ref == null) {
            return;
        }
        long size = parseLong(msg.getAttributes().get(ATTR_SIZE));
        try {
            FileStore.UploadSession session = files.beginUpload(
                    msg.getAttributes().get(ATTR_NAME), size, msg.getAttributes().get(ATTR_MIME));
            session.setRecipient(msg.getTo());
            uploads.put(ref, session);
        } catch (IOException e) {
            // Не приняли файл (например, велик) — сообщаем отправителю и игнорируем чанки.
            connection.send(new Message(MessageType.ERROR, "server", nick,
                    "Файл не принят: " + e.getMessage(), System.currentTimeMillis()));
        }
    }

    /** Очередной чанк: декодируем Base64 и дописываем на диск. */
    private void handleFileChunk(Message msg) {
        String ref = msg.getAttributes().get(ATTR_REF);
        FileStore.UploadSession session = ref == null ? null : uploads.get(ref);
        if (session == null || msg.getBody() == null) {
            return; // загрузка не открыта (например, была отклонена) — молча пропускаем
        }
        try {
            session.write(Base64.getDecoder().decode(msg.getBody()));
        } catch (IOException | IllegalArgumentException e) {
            session.abort();
            uploads.remove(ref);
            System.out.println("[files] Загрузка " + ref + " прервана: " + e.getMessage());
        }
    }

    /**
     * Конец загрузки: фиксируем файл и пускаем в чат обычное сообщение-ссылку.
     * Дальше всё штатно — доставка/оффлайн-очередь/журнал/галочки (ПР13/14).
     */
    private void handleFileEnd(Message msg) {
        String ref = msg.getAttributes().get(ATTR_REF);
        FileStore.UploadSession session = ref == null ? null : uploads.remove(ref);
        if (session == null) {
            return;
        }
        try {
            session.finish();
        } catch (IOException e) {
            session.abort();
            System.out.println("[files] Не удалось сохранить файл " + ref + ": " + e.getMessage());
            return;
        }
        Message ref2 = new Message(MessageType.MESSAGE, nick, session.recipient(),
                session.name(), System.currentTimeMillis());
        ref2.getAttributes().put(MessageStore.ATTR_ID, ref); // id ссылки = хэндл (для галочек)
        ref2.getAttributes().put(ATTR_FILE, "1");
        ref2.getAttributes().put(ATTR_FILE_ID, session.fileId());
        ref2.getAttributes().put(ATTR_NAME, session.name());
        ref2.getAttributes().put(ATTR_SIZE, Long.toString(session.written()));
        if (session.mime() != null) {
            ref2.getAttributes().put(ATTR_MIME, session.mime());
        }
        router.route(ref2, this);
    }

    /** Запрос на скачивание: отдаём файл обратно той же тройкой FILE_START/CHUNK/END. */
    private void handleFileGet(Message msg) throws IOException {
        String fileId = msg.getAttributes().get(ATTR_FILE_ID);
        FileStore.FileMeta meta = fileId == null ? null : files.meta(fileId);
        if (meta == null) {
            connection.send(new Message(MessageType.ERROR, "server", nick,
                    "Файл недоступен", System.currentTimeMillis()));
            return;
        }
        Message start = new Message(MessageType.FILE_START, "server", nick, null,
                System.currentTimeMillis());
        start.getAttributes().put(ATTR_REF, fileId);
        start.getAttributes().put(ATTR_NAME, meta.name());
        start.getAttributes().put(ATTR_SIZE, Long.toString(meta.size()));
        if (meta.mime() != null) {
            start.getAttributes().put(ATTR_MIME, meta.mime());
        }
        connection.send(start);

        byte[] buf = new byte[DOWNLOAD_CHUNK];
        try (InputStream in = Files.newInputStream(files.path(fileId))) {
            int read;
            while ((read = in.read(buf)) > 0) {
                Message chunk = new Message(MessageType.FILE_CHUNK, "server", nick,
                        Base64.getEncoder().encodeToString(
                                read == buf.length ? buf : java.util.Arrays.copyOf(buf, read)),
                        System.currentTimeMillis());
                chunk.getAttributes().put(ATTR_REF, fileId);
                connection.send(chunk);
            }
        }
        Message end = new Message(MessageType.FILE_END, "server", nick, null,
                System.currentTimeMillis());
        end.getAttributes().put(ATTR_REF, fileId);
        connection.send(end);
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Принудительно закрывает соединение, признанное мёртвым «жнецом». Закрытие
     * сокета разблокирует {@link #run()} (его {@code receive()} вернёт null или
     * бросит исключение), и штатный {@code finally → disconnect()} сам снимет
     * клиента с регистрации и разошлёт USER_LEFT.
     */
    public void disconnectStale() {
        System.out.println("[server] Нет ответа от " + nick
                + " — закрываю мёртвое соединение");
        connection.close();
    }

    private void disconnect() {
        // Недокачанные загрузки — отменяем, чтобы не плодить .part-файлы.
        for (FileStore.UploadSession session : uploads.values()) {
            session.abort();
        }
        uploads.clear();
        if (nick != null) {
            registry.unregister(nick);
            System.out.println("[server] Вышел: " + nick);
            registry.broadcast(new Message(MessageType.USER_LEFT, "server", null, nick,
                    System.currentTimeMillis()), null);
            registry.broadcastUserList();
        }
        connection.close();
    }

    /** Отправка сообщения этому клиенту (вызывается из реестра/роутера). */
    public void send(Message message) throws IOException {
        connection.send(message);
    }

    public String getNick() {
        return nick;
    }
}
