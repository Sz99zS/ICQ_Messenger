package org.example.server;

import org.example.net.Connection;
import org.example.protocol.Message;
import org.example.protocol.MessageType;
import org.example.protocol.ProtocolFactory;

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

    private final Connection connection;
    private final ClientRegistry registry;
    private final MessageRouter router;
    private String nick;

    public ClientHandler(Socket socket, ClientRegistry registry, MessageRouter router)
            throws IOException {
        this.connection = new Connection(socket, ProtocolFactory.createCodec());
        this.registry = registry;
        this.router = router;
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
                registry.touch(nick);                 // любое сообщение = активность
                registry.broadcastUserListIfChanged(); // вернулся из AWAY → обновить статус
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
        registry.broadcast(new Message(MessageType.USER_JOINED, "server", null, nick,
                System.currentTimeMillis()), nick);
        registry.broadcastUserList();
        return true;
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
