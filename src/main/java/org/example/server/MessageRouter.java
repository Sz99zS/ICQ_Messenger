package org.example.server;

import org.example.protocol.Message;
import org.example.protocol.MessageType;
import org.example.server.store.MessageStore;

/**
 * Маршрутизатор сообщений: решает, кому доставить пришедшее от клиента
 * сообщение — всем (broadcast) или конкретному адресату (личное).
 *
 * <p>Это реализация требования (c): собственная система обмена сообщениями
 * между клиентами через сервер.
 *
 * <p>На ПР9 каждое доставленное {@code MESSAGE} ещё и сохраняется в
 * {@link MessageStore} — чтобы пережить перезапуск сервера и быть проигранным
 * заново при следующем входе адресатов. Личное сообщение оффлайн-пользователю
 * тоже сохраняется и придёт ему историей, когда он подключится.
 */
public class MessageRouter {

    private final ClientRegistry registry;
    private final MessageStore store;

    public MessageRouter(ClientRegistry registry, MessageStore store) {
        this.registry = registry;
        this.store = store;
    }

    /**
     * Маршрутизирует сообщение, пришедшее от {@code sender}.
     */
    public void route(Message message, ClientHandler sender) {
        switch (message.getType()) {
            case MESSAGE -> {
                store.append(message);   // сперва фиксируем в журнале, затем доставляем
                deliver(message, sender);
            }
            case TYPING -> deliver(message, sender);
            case PING -> { /* keep-alive: ничего не пересылаем */ }
            default -> System.out.println("[server] Игнорирую от " + sender.getNick()
                    + ": неподдерживаемый тип " + message.getType());
        }
    }

    private void deliver(Message message, ClientHandler sender) {
        if (message.isBroadcast()) {
            // Всем, кроме отправителя — у него сообщение уже отображено локально.
            registry.broadcast(message, sender.getNick());
        } else {
            boolean delivered = registry.sendTo(message.getTo(), message);
            if (!delivered) {
                tryNotifyOffline(sender, message.getTo());
            }
        }
    }

    private void tryNotifyOffline(ClientHandler sender, String target) {
        try {
            sender.send(new Message(MessageType.ERROR, "server", sender.getNick(),
                    "Пользователь не в сети: " + target, System.currentTimeMillis()));
        } catch (Exception ignored) { }
    }
}
