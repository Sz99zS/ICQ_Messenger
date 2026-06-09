package org.example.server.store;

import org.example.protocol.Message;
import org.example.protocol.ProtocolCodec;
import org.example.protocol.ProtocolException;
import org.example.protocol.ProtocolFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Персистентное хранилище переписки (ПР9) с ротацией по размеру (ПР11).
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
 * <p><b>Ротация (ПР11).</b> Журнал не растёт бесконечно: хранятся только
 * последние {@link #maxMessages} сообщений. Обычная запись дешёвая (дописывание
 * строки в конец). Когда в памяти накапливается {@link #compactThreshold}
 * сообщений, выполняется «компакция»: старые отбрасываются, а файл атомарно
 * переписывается из памяти. Так амортизированная стоимость записи остаётся
 * низкой (полная перезапись — раз в несколько сотен сообщений), а размер файла
 * и потребление памяти ограничены сверху.
 *
 * <p>Класс потокобезопасен: запись из потока-клиента и выборка истории при входе
 * сериализуются по одному монитору. Вся хранимая история держится ещё и в
 * памяти — выборка для входящего клиента не читает файл повторно.
 */
public final class MessageStore {

    /** Путь к журналу по умолчанию (относительно рабочей директории сервера). */
    public static final Path DEFAULT_FILE = Path.of("data", "messages.log");

    /** Сколько последних сообщений хранить по умолчанию. */
    public static final int DEFAULT_MAX_MESSAGES = 2000;

    /**
     * Атрибут со стабильным идентификатором сообщения (ПР13). Присваивается
     * сервером при первом сохранении и едет дальше и в журнал, и по сети — по
     * нему очередь оффлайн-доставки ({@code OfflineStore}) отличает недоставленное
     * от уже виденного, а будущие галочки «прочитано» смогут адресовать сообщение.
     */
    public static final String ATTR_ID = "id";

    private final Path file;
    private final ProtocolCodec codec = ProtocolFactory.createCodec();
    /** Вся сохранённая история в порядке поступления (под {@code this}-монитором). */
    private final List<Message> history = new ArrayList<>();

    /** Верхняя граница числа хранимых сообщений (после компакции). */
    private final int maxMessages;
    /** Достигнув этого размера в памяти, запускаем компакцию (обрезку + перезапись). */
    private final int compactThreshold;
    /**
     * Счётчик для следующего {@link #ATTR_ID}. Сидируется максимумом из журнала
     * при загрузке, чтобы id оставались монотонными и не сталкивались после
     * перезапуска (под {@code this}-монитором).
     */
    private long nextId = 1;

    /**
     * Открывает (создавая при необходимости) журнал и загружает прошлую историю
     * в память. Битые строки пропускаются, чтобы один испорченный кадр не ронял
     * запуск сервера. Если в файле оказалось больше {@link #maxMessages}
     * сообщений — журнал сразу ужимается до лимита.
     */
    public MessageStore(Path file, int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages должен быть > 0: " + maxMessages);
        }
        this.file = file;
        this.maxMessages = maxMessages;
        // Запас, чтобы перезаписывать файл не на каждое сообщение сверх лимита,
        // а пачками. Минимум 1 — корректно даже для крошечных лимитов (тесты).
        this.compactThreshold = maxMessages + Math.max(1, maxMessages / 5);
        load();
    }

    public MessageStore(Path file) {
        this(file, DEFAULT_MAX_MESSAGES);
    }

    public MessageStore() {
        this(DEFAULT_FILE, DEFAULT_MAX_MESSAGES);
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
        // Файл мог скопиться сверх лимита (например, лимит уменьшили) — ужимаем.
        if (history.size() > maxMessages) {
            compactLocked();
        }
        seedNextId();
    }

    /**
     * Сдвигает счётчик id за максимальный из уже сохранённых — так после
     * перезапуска новые сообщения не получают id, который ещё «висит» в очереди
     * оффлайн-доставки. Старые сообщения без id (журналы до ПР13) игнорируются.
     */
    private void seedNextId() {
        long maxId = 0;
        for (Message m : history) {
            String idStr = m.getAttributes().get(ATTR_ID);
            if (idStr != null) {
                try {
                    maxId = Math.max(maxId, Long.parseLong(idStr));
                } catch (NumberFormatException ignored) {
                    // не наш формат id — не учитываем
                }
            }
        }
        nextId = maxId + 1;
    }

    /**
     * Сохраняет одно сообщение: дописывает строку в файл и в память. По
     * достижении порога запускает ротацию. Ошибку записи логируем, но не
     * пробрасываем — сбой диска не должен ронять доставку.
     */
    public synchronized void append(Message message) {
        // Присваиваем стабильный id, если его ещё нет: попадёт и в память, и в
        // файл (атрибут сериализуется кодеком), и уедет адресату по сети.
        if (message.getAttributes().get(ATTR_ID) == null) {
            message.getAttributes().put(ATTR_ID, Long.toString(nextId++));
        }
        history.add(message);
        if (history.size() >= compactThreshold) {
            // Пора ротировать: обрезаем старые и перезаписываем файл целиком
            // (новое сообщение уже в history, попадёт в перезапись).
            compactLocked();
            return;
        }
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

    /** Текущее число хранимых сообщений (для тестов/диагностики). */
    public synchronized int size() {
        return history.size();
    }

    /**
     * Ротация: оставляет в памяти только последние {@link #maxMessages}
     * сообщений и переписывает файл из этого «хвоста». Вызывается под монитором.
     */
    private void compactLocked() {
        int excess = history.size() - maxMessages;
        if (excess > 0) {
            history.subList(0, excess).clear();
            System.out.println("[store] Ротация журнала: отброшено старых сообщений: "
                    + excess + ", осталось: " + history.size());
        }
        rewriteLocked();
    }

    /** Полностью переписывает файл из памяти атомарно (через временный файл). */
    private void rewriteLocked() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Message m : history) {
                    writer.write(codec.encode(m));
                    writer.newLine();
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                // Некоторые ФС не умеют атомарный move — заменяем неатомарно.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | ProtocolException e) {
            System.out.println("[store] Не удалось переписать журнал: " + e.getMessage());
        }
    }
}
