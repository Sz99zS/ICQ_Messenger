package org.example.net;

import org.example.protocol.Message;
import org.example.protocol.ProtocolCodec;
import org.example.protocol.ProtocolException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Обёртка над {@link Socket}: прячет потоки ввода-вывода и кодек, давая
 * простые операции «отправить/принять {@link Message}».
 *
 * <p>Один и тот же класс используют и сервер ({@code ClientHandler}), и клиент
 * ({@code ClientService}) — код чтения/записи не дублируется. Обмен идёт
 * построчно в UTF-8: одна строка = одно сообщение.
 */
public class Connection implements AutoCloseable {

    private final Socket socket;
    private final ProtocolCodec codec;
    private final BufferedReader in;
    private final BufferedWriter out;

    public Connection(Socket socket, ProtocolCodec codec) throws IOException {
        this.socket = socket;
        this.codec = codec;
        this.in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    /** Отправляет сообщение (потокобезопасно для конкурентных отправок). */
    public synchronized void send(Message message) throws IOException {
        try {
            out.write(codec.encode(message));
            out.newLine();
            out.flush();
        } catch (ProtocolException e) {
            throw new IOException("Ошибка кодирования сообщения", e);
        }
    }

    /**
     * Блокирующее чтение одного сообщения.
     *
     * @return сообщение или {@code null}, если соединение закрыто (конец потока)
     */
    public Message receive() throws IOException {
        String line = in.readLine();
        if (line == null) {
            return null; // соединение закрыто другой стороной
        }
        try {
            return codec.decode(line);
        } catch (ProtocolException e) {
            throw new IOException("Ошибка разбора сообщения: " + line, e);
        }
    }

    public String getRemoteAddress() {
        return socket.getRemoteSocketAddress() != null
                ? socket.getRemoteSocketAddress().toString()
                : "unknown";
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
