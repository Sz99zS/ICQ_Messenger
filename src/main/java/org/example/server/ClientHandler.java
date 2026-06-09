package org.example.server;

import org.example.net.Connection;
import org.example.protocol.Message;
import org.example.protocol.MessageType;
import org.example.protocol.ProtocolFactory;
import org.example.server.store.AccountStore;
import org.example.server.store.MessageStore;
import org.example.server.store.OfflineStore;

import java.io.IOException;
import java.net.Socket;
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

    private final Connection connection;
    private final ClientRegistry registry;
    private final MessageRouter router;
    private final MessageStore store;
    private final AccountStore accounts;
    private final OfflineStore offline;
    private String nick;

    public ClientHandler(Socket socket, ClientRegistry registry, MessageRouter router,
                         MessageStore store, AccountStore accounts, OfflineStore offline)
            throws IOException {
        this.connection = new Connection(socket, ProtocolFactory.createCodec());
        this.registry = registry;
        this.router = router;
        this.store = store;
        this.accounts = accounts;
        this.offline = offline;
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
                router.route(msg, this);
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
     */
    private void sendHistory() throws IOException {
        Set<String> unread = offline.pendingFor(nick);
        for (Message past : store.historyFor(nick)) {
            Message replay = new Message(past.getType(), past.getFrom(), past.getTo(),
                    past.getBody(), past.getTimestamp());
            replay.getAttributes().putAll(past.getAttributes()); // переносим id и пр.
            String id = past.getAttributes().get(MessageStore.ATTR_ID);
            // Недоставленное (в очереди) — как «живое»; всё прочее — историей.
            if (id == null || !unread.contains(id)) {
                replay.getAttributes().put(ATTR_HISTORY, "1");
            }
            connection.send(replay);
        }
        offline.clear(nick);
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
