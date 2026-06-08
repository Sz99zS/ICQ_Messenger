package org.example.server;

import org.example.net.Connection;
import org.example.protocol.Message;
import org.example.protocol.MessageType;
import org.example.protocol.ProtocolFactory;
import org.example.server.store.MessageStore;

import java.io.IOException;
import java.net.Socket;

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
    private String nick;

    public ClientHandler(Socket socket, ClientRegistry registry, MessageRouter router,
                         MessageStore store) throws IOException {
        this.connection = new Connection(socket, ProtocolFactory.createCodec());
        this.registry = registry;
        this.router = router;
        this.store = store;
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

    /** Обрабатывает вход: проверяет ник, регистрирует, рассылает события. */
    private boolean handleLogin() throws IOException {
        Message first = connection.receive();
        if (first == null || first.getType() != MessageType.LOGIN) {
            return false;
        }
        String requested = first.getFrom();
        if (requested == null || requested.isBlank()) {
            connection.send(new Message(MessageType.LOGIN_FAIL, "server", null,
                    "Пустой ник", System.currentTimeMillis()));
            return false;
        }
        if (!registry.register(requested, this)) {
            connection.send(new Message(MessageType.LOGIN_FAIL, "server", requested,
                    "Ник уже занят", System.currentTimeMillis()));
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
     * Проигрывает вошедшему его историю (ПР9): общий чат плюс личные диалоги с
     * его участием. Каждый кадр — обычный {@code MESSAGE} с меткой
     * {@link #ATTR_HISTORY}, чтобы клиент отрисовал его без бейджа «непрочитано».
     */
    private void sendHistory() throws IOException {
        for (Message past : store.historyFor(nick)) {
            Message replay = new Message(past.getType(), past.getFrom(), past.getTo(),
                    past.getBody(), past.getTimestamp());
            replay.getAttributes().put(ATTR_HISTORY, "1");
            connection.send(replay);
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
