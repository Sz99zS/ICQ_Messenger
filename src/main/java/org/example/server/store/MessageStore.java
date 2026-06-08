package org.example.server.store;

import org.example.protocol.Message;
import org.example.protocol.ProtocolCodec;
import org.example.protocol.ProtocolException;
import org.example.protocol.ProtocolFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Персистентное хранилище переписки (ПР9).
 *
 * <p>Делает мессенджер устойчивым к перезапуску сервера: все доставленные
 * {@code MESSAGE} (и общие, и личные) дописываются в файл-журнал, а при входе
 * клиента релевантная ему история проигрывается заново.
 *
 * <p>Формат файла повторяет проводной протокол — <b>одна строка = одно
 * сообщение</b> в том же XML, что гоняется по сети ({@link XmlProtocolCodec}).
 * Поэтому отдельный формат хранения не понадобился: журнал переиспользует уже
 * существующий кодек, а файл можно прочитать тем же декодером.
 *
 * <p>Класс потокобезопасен: запись из потока-клиента и выборка истории при входе
 * сериализуются по одному монитору. Вся история держится ещё и в памяти —
 * выборка для входящего клиента не читает файл повторно.
 */
public final class MessageStore {

    /** Путь к журналу по умолчанию (относительно рабочей директории сервера). */
    public static final Path DEFAULT_FILE = Path.of("data", "messages.log");

    private final Path file;
    private final ProtocolCodec codec = ProtocolFactory.createCodec();
    /** Вся сохранённая история в порядке поступления (под {@code this}-монитором). */
    private final List<Message> history = new ArrayList<>();

    /**
     * Открывает (создавая при необходимости) журнал и загружает прошлую историю
     * в память. Битые строки пропускаются, чтобы один испорченный кадр не ронял
     * запуск сервера.
     */
    public MessageStore(Path file) {
        this.file = file;
        load();
    }

    public MessageStore() {
        this(DEFAULT_FILE);
    }

    private synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int bad = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    history.add(codec.decode(line));
                } catch (ProtocolException e) {
                    bad++;
                }
            }
            System.out.println("[store] Загружено сообщений: " + history.size()
                    + (bad > 0 ? " (пропущено битых: " + bad + ")" : ""));
        } catch (IOException e) {
            System.out.println("[store] Не удалось прочитать историю: " + e.getMessage());
        }
    }

    /**
     * Сохраняет одно сообщение: дописывает строку в файл и в память. Ошибку
     * записи логируем, но не пробрасываем — сбой диска не должен ронять доставку.
     */
    public synchronized void append(Message message) {
        history.add(message);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(codec.encode(message));
                writer.newLine();
            }
        } catch (IOException | ProtocolException e) {
            System.out.println("[store] Не удалось сохранить сообщение: " + e.getMessage());
        }
    }

    /**
     * Возвращает историю, релевантную пользователю {@code nick}: все сообщения
     * общего чата плюс личные, где он отправитель или получатель. Порядок —
     * хронологический (как поступали). Снимок — безопасен для перебора вне
     * блокировки.
     */
    public synchronized List<Message> historyFor(String nick) {
        List<Message> result = new ArrayList<>();
        for (Message m : history) {
            if (m.isBroadcast()
                    || nick.equals(m.getTo())
                    || nick.equals(m.getFrom())) {
                result.add(m);
            }
        }
        return result;
    }
}
