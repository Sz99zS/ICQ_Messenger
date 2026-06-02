package org.example.server;

/**
 * Точка входа серверной части мессенджера ICQ.
 *
 * <p>Пока это заглушка. В следующих лабораторных здесь появится
 * {@link java.net.ServerSocket}, приём подключений клиентов и обработка
 * XML-протокола из пакета {@code org.example.protocol}.
 */
public class ChatServer {

    /** Порт, на котором сервер будет слушать подключения. */
    public static final int DEFAULT_PORT = 12345;

    public static void main(String[] args) {
        System.out.println("Сервер запущен");
    }
}
