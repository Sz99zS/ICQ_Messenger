package org.example.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final MessageRouter router = new MessageRouter(registry);
    // Поток на клиента: их число заранее неизвестно — берём кэширующий пул.
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public ChatServer(int port) {
        this.port = port;
    }

    public void start() {
        System.out.println("[server] Запуск на порту " + port + " ...");
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[server] Готов принимать подключения.");
            while (true) {
                Socket socket = serverSocket.accept();
                acceptClient(socket);
            }
        } catch (IOException e) {
            System.out.println("[server] Критическая ошибка сервера: " + e.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    private void acceptClient(Socket socket) {
        try {
            ClientHandler handler = new ClientHandler(socket, registry, router);
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
