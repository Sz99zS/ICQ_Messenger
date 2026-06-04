package org.example.server;

import org.example.protocol.Message;
import org.example.protocol.MessageType;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Потокобезопасный реестр онлайн-клиентов (ник → обработчик).
 *
 * <p>К реестру обращаются разные потоки (по одному на клиента), поэтому внутри
 * {@link ConcurrentHashMap}. Здесь же — широковещательная рассылка и адресная
 * доставка.
 */
public class ClientRegistry {

    private final ConcurrentHashMap<String, ClientHandler> clients = new ConcurrentHashMap<>();
    /**
     * Время последней <em>пользовательской</em> активности ника (мс) — реальные
     * сообщения (MESSAGE/TYPING). На его основе вычисляется AWAY. PING сюда НЕ
     * входит, иначе heartbeat не давал бы уйти в AWAY.
     */
    private final ConcurrentHashMap<String, Long> lastActivity = new ConcurrentHashMap<>();
    /**
     * Время последнего <em>любого</em> кадра от ника (мс), включая PING. На его
     * основе «жнец» определяет мёртвые соединения.
     */
    private final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();
    /** Последний разосланный список — чтобы не слать USER_LIST, если ничего не изменилось. */
    private volatile String lastBroadcastList = "";

    /** Через сколько мс бездействия клиент считается «отошёл» (AWAY). */
    private static final long AWAY_AFTER_MS = 20_000;

    /**
     * Регистрирует клиента под ником.
     *
     * @return {@code true}, если ник свободен и регистрация удалась
     */
    public boolean register(String nick, ClientHandler handler) {
        boolean ok = clients.putIfAbsent(nick, handler) == null;
        if (ok) {
            long now = System.currentTimeMillis();
            lastActivity.put(nick, now);
            lastSeen.put(nick, now);
        }
        return ok;
    }

    public void unregister(String nick) {
        if (nick != null) {
            clients.remove(nick);
            lastActivity.remove(nick);
            lastSeen.remove(nick);
        }
    }

    /**
     * Реальная активность пользователя (MESSAGE/TYPING): сбрасывает таймер AWAY
     * и одновременно подтверждает живость соединения.
     */
    public void recordActivity(String nick) {
        if (nick != null) {
            long now = System.currentTimeMillis();
            lastActivity.put(nick, now);
            lastSeen.put(nick, now);
        }
    }

    /**
     * Heartbeat (PING): подтверждает только живость соединения, но НЕ считается
     * активностью — пользователь может оставаться AWAY, пока шлёт пинги.
     */
    public void recordHeartbeat(String nick) {
        if (nick != null) {
            lastSeen.put(nick, System.currentTimeMillis());
        }
    }

    public ClientHandler get(String nick) {
        return clients.get(nick);
    }

    /** Список ников всех, кто сейчас онлайн. */
    public List<String> onlineNicks() {
        return List.copyOf(clients.keySet());
    }

    /** Статус ника: ONLINE, либо AWAY при длительном бездействии. */
    private String statusOf(String nick) {
        Long last = lastActivity.get(nick);
        if (last == null) {
            return "ONLINE";
        }
        return (System.currentTimeMillis() - last >= AWAY_AFTER_MS) ? "AWAY" : "ONLINE";
    }

    /** Тело USER_LIST: {@code ник:СТАТУС,ник:СТАТУС,...}. */
    public String formatUserList() {
        StringBuilder sb = new StringBuilder();
        for (String nick : clients.keySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(nick).append(':').append(statusOf(nick));
        }
        return sb.toString();
    }

    /** Безусловно рассылает всем актуальный список онлайн со статусами. */
    public synchronized void broadcastUserList() {
        String current = formatUserList();
        lastBroadcastList = current;
        broadcast(new Message(MessageType.USER_LIST, "server", null, current,
                System.currentTimeMillis()), null);
    }

    /** Рассылает список, только если он изменился с прошлой рассылки (для тика статусов). */
    public synchronized void broadcastUserListIfChanged() {
        String current = formatUserList();
        if (!current.equals(lastBroadcastList)) {
            lastBroadcastList = current;
            broadcast(new Message(MessageType.USER_LIST, "server", null, current,
                    System.currentTimeMillis()), null);
        }
    }

    /** Отправляет сообщение всем клиентам, кроме указанного ника. */
    public void broadcast(Message message, String exceptNick) {
        for (var entry : clients.entrySet()) {
            if (entry.getKey().equals(exceptNick)) {
                continue;
            }
            trySend(entry.getValue(), message);
        }
    }

    /** Отправляет сообщение конкретному клиенту (если он онлайн). */
    public boolean sendTo(String nick, Message message) {
        ClientHandler handler = clients.get(nick);
        if (handler == null) {
            return false;
        }
        trySend(handler, message);
        return true;
    }

    public Set<String> nicks() {
        return clients.keySet();
    }

    /**
     * «Жнец» мёртвых соединений: закрывает тех, от кого не было ни одного кадра
     * (включая PING) дольше {@code deadAfterMs}. Само снятие с регистрации и
     * рассылку USER_LEFT выполнит поток клиента в {@code finally} после того,
     * как закрытый сокет разблокирует его чтение.
     *
     * @return сколько соединений признано мёртвыми и закрыто
     */
    public int reapStale(long deadAfterMs) {
        long now = System.currentTimeMillis();
        int reaped = 0;
        for (var entry : clients.entrySet()) {
            Long seen = lastSeen.get(entry.getKey());
            if (seen != null && now - seen > deadAfterMs) {
                entry.getValue().disconnectStale();
                reaped++;
            }
        }
        return reaped;
    }

    private void trySend(ClientHandler handler, Message message) {
        try {
            handler.send(message);
        } catch (IOException e) {
            // Сбойного клиента снимаем — его поток сам завершится и почистит реестр.
            System.out.println("[server] Не удалось доставить сообщение " + handler.getNick()
                    + ": " + e.getMessage());
        }
    }
}
