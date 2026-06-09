package org.example.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.example.server.store.AccountStore;
import org.example.server.store.MessageStore;
import org.example.server.store.OfflineStore;

/**
 * Серверная часть мессенджера ICQ.
 *
 * <p>Слушает {@link ServerSocket} на заданном порту и на каждое подключение
 * запускает {@link ClientHandler} в пуле потоков. Маршрутизацию и учёт онлайн
 * клиентов ведут {@link MessageRouter} и {@link ClientRegistry}.
 *
 * <p>Запуск: {@code mvn exec:java} либо {@code java ... ChatServer [порт]}.
 */
public class ChatServer {

    /** Порт по умолчанию, если не передан аргументом. */
    public static final int DEFAULT_PORT = 12345;

    private final int port;
    private final ClientRegistry registry = new ClientRegistry();
    // Журнал переписки: переживает перезапуск сервера и отдаёт историю при входе.
    private final MessageStore store = new MessageStore();
    // Учётные записи (ПР12): регистрация и проверка пароля при входе.
    private final AccountStore accounts = new AccountStore();
    // Очередь оффлайн-доставки (ПР13): личка для тех, кто сейчас не в сети.
    private final OfflineStore offline = new OfflineStore();
    private final MessageRouter router = new MessageRouter(registry, store, accounts, offline);
    // Поток на клиента: их число заранее неизвестно — берём кэширующий пул.
    private final ExecutorService pool = Executors.newCachedThreadPool();
    // Периодически пересчитывает статусы (ONLINE/AWAY) и реапит мёртвые соединения.
    private final ScheduledExecutorService statusTicker = Executors.newSingleThreadScheduledExecutor();

    /** Соединение без единого кадра (включая PING) дольше этого времени — мёртвое. */
    private static final long DEAD_AFTER_MS = 15_000;

    public ChatServer(int port) {
        this.port = port;
    }

    public void start() {
        System.out.println("[server] Запуск на порту " + port + " ...");
        // Раз в 10с проверяем, не «отошёл» ли кто-то (AWAY), и рассылаем изменения.
        statusTicker.scheduleAtFixedRate(registry::broadcastUserListIfChanged,
                10, 10, TimeUnit.SECONDS);
        // Раз в 5с «жнём» мёртвые соединения (нет кадров/PING дольше DEAD_AFTER_MS).
        statusTicker.scheduleAtFixedRate(() -> registry.reapStale(DEAD_AFTER_MS),
                5, 5, TimeUnit.SECONDS);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[server] Готов принимать подключения.");
            while (true) {
                Socket socket = serverSocket.accept();
                acceptClient(socket);
            }
        } catch (IOException e) {
            System.out.println("[server] Критическая ошибка сервера: " + e.getMessage());
        } finally {
            statusTicker.shutdownNow();
            pool.shutdownNow();
        }
    }

    private void acceptClient(Socket socket) {
        try {
            ClientHandler handler = new ClientHandler(socket, registry, router, store, accounts, offline);
            pool.submit(handler);
        } catch (IOException e) {
            System.out.println("[server] Не удалось принять клиента: " + e.getMessage());
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("[server] Некорректный порт '" + args[0]
                        + "', использую " + DEFAULT_PORT);
            }
        }
        new ChatServer(port).start();
    }
}
