package org.example.server;

import org.example.protocol.Message;

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
     * Регистрирует клиента под ником.
     *
     * @return {@code true}, если ник свободен и регистрация удалась
     */
    public boolean register(String nick, ClientHandler handler) {
        return clients.putIfAbsent(nick, handler) == null;
    }

    public void unregister(String nick) {
        if (nick != null) {
            clients.remove(nick);
        }
    }

    public ClientHandler get(String nick) {
        return clients.get(nick);
    }

    /** Список ников всех, кто сейчас онлайн. */
    public List<String> onlineNicks() {
        return List.copyOf(clients.keySet());
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
