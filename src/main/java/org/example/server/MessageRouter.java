package org.example.server;

import org.example.protocol.Message;
import org.example.protocol.MessageType;
import org.example.server.store.AccountStore;
import org.example.server.store.MessageStore;
import org.example.server.store.OfflineStore;
import org.example.server.store.ReadReceiptStore;

import java.util.List;

/**
 * Маршрутизатор сообщений: решает, кому доставить пришедшее от клиента
 * сообщение — всем (broadcast) или конкретному адресату (личное).
 *
 * <p>Это реализация требования (c): собственная система обмена сообщениями
 * между клиентами через сервер.
 *
 * <p>На ПР9 каждое доставленное {@code MESSAGE} ещё и сохраняется в
 * {@link MessageStore} — чтобы пережить перезапуск сервера и быть проигранным
 * заново при следующем входе адресатов.
 *
 * <p><b>Оффлайн-доставка (ПР13).</b> Если личное сообщение некому отдать прямо
 * сейчас, поведение зависит от адресата: <ul>
 *   <li>аккаунт существует, но владелец оффлайн → ставим сообщение в
 *       {@link OfflineStore} (придёт ему «непрочитанным» при входе) и
 *       <b>не</b> шлём отправителю ложный {@code ERROR};</li>
 *   <li>такого аккаунта нет вовсе → честный {@code ERROR}: сообщение никогда не
 *       будет доставлено, и отправитель должен это знать.</li>
 * </ul>
 */
public class MessageRouter {

    private final ClientRegistry registry;
    private final MessageStore store;
    private final AccountStore accounts;
    private final OfflineStore offline;
    private final ReadReceiptStore readReceipts;

    public MessageRouter(ClientRegistry registry, MessageStore store, AccountStore accounts,
                         OfflineStore offline, ReadReceiptStore readReceipts) {
        this.registry = registry;
        this.store = store;
        this.accounts = accounts;
        this.offline = offline;
        this.readReceipts = readReceipts;
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
            case READ -> handleRead(message, sender);
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
            if (delivered) {
                // Личное сообщение дошло вживую — сразу подтверждаем отправителю.
                notifyDelivered(sender.getNick(), message.getTo(),
                        message.getAttributes().get(MessageStore.ATTR_ID));
            } else {
                handleUndelivered(sender, message);
            }
        }
    }

    /**
     * Квитанция «прочитано» (ПР14): читатель {@code reader} открыл диалог с
     * {@code peer}. Помечаем прочитанными все личные сообщения {@code peer →
     * reader} и пересылаем {@code READ} их отправителю, чтобы он увидел ✓✓.
     */
    private void handleRead(Message message, ClientHandler reader) {
        String peer = message.getTo();
        if (peer == null || peer.isBlank()) {
            return;
        }
        List<String> ids = store.privateIdsFromTo(peer, reader.getNick());
        if (ids.isEmpty()) {
            return; // читать нечего — лишних квитанций не плодим
        }
        readReceipts.markRead(ids);
        // Отправителю (peer): «reader прочитал твою переписку с ним».
        registry.sendTo(peer, new Message(MessageType.READ, reader.getNick(), peer, null,
                System.currentTimeMillis()));
    }

    /** Шлёт отправителю {@code sender} квитанцию «доставлено» по сообщению {@code id}. */
    private void notifyDelivered(String sender, String recipient, String id) {
        if (id == null) {
            return;
        }
        Message receipt = new Message(MessageType.DELIVERED, recipient, sender, null,
                System.currentTimeMillis());
        receipt.getAttributes().put(MessageStore.ATTR_ID, id);
        registry.sendTo(sender, receipt);
    }

    /**
     * Личное сообщение не удалось отдать сейчас. Если адресат существует — копим
     * в очереди оффлайн-доставки (дойдёт «непрочитанным» при его входе); если
     * такого ника нет — честно сообщаем отправителю об ошибке.
     */
    private void handleUndelivered(ClientHandler sender, Message message) {
        String target = message.getTo();
        if (accounts.exists(target)) {
            offline.enqueue(target, message.getAttributes().get(MessageStore.ATTR_ID));
        } else {
            tryNotifyError(sender, "Нет такого пользователя: " + target);
        }
    }

    private void tryNotifyError(ClientHandler sender, String reason) {
        try {
            sender.send(new Message(MessageType.ERROR, "server", sender.getNick(),
                    reason, System.currentTimeMillis()));
        } catch (Exception ignored) { }
    }
}
