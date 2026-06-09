package org.example.server.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Очередь недоставленной личной переписки (ПР13).
 *
 * <p>Раньше личное сообщение оффлайн-адресату молча оседало в общем журнале и
 * при следующем входе проигрывалось ему как обычная история — без бейджа
 * «непрочитано», а отправителю при этом летел ложный {@code ERROR «не в сети»}.
 * Этот стор отделяет <b>недоставленное</b> от истории: пока адресат оффлайн, id
 * адресованных ему сообщений копятся здесь, а при входе сервер отдаёт их как
 * «живые» (с непрочитанным), затем очередь чистится.
 *
 * <p>Храним именно {@link MessageStore#ATTR_ID id}, а не сами сообщения: тело
 * уже лежит в журнале переписки, дублировать его незачем. При входе адресата по
 * этим id отбираются нужные кадры из истории.
 *
 * <p><b>Формат файла.</b> По аналогии с {@link AccountStore}/{@link MessageStore}:
 * текстовый журнал, одна строка = одна пара через табуляцию:
 * <pre>{@code получатель \t idСообщения}</pre>
 * Постановка в очередь — дешёвый append; снятие очереди ({@link #clear}) целиком
 * переписывает файл без строк этого получателя.
 *
 * <p>Класс потокобезопасен: всё сериализовано по монитору {@code this}. Очередь
 * держится в памяти — выборка при входе не читает файл.
 */
public final class OfflineStore {

    /** Путь по умолчанию (рядом с журналом переписки и учётками). */
    public static final Path DEFAULT_FILE = Path.of("data", "offline.log");

    private final Path file;
    /** Получатель → id недоставленных ему сообщений (порядок поступления). */
    private final Map<String, LinkedHashSet<String>> pending = new LinkedHashMap<>();

    public OfflineStore(Path file) {
        this.file = file;
        load();
    }

    public OfflineStore() {
        this(DEFAULT_FILE);
    }

    /**
     * Ставит сообщение {@code messageId} в очередь к оффлайн-получателю
     * {@code recipient}. Дублирующиеся id (повторная постановка) игнорируются.
     */
    public synchronized void enqueue(String recipient, String messageId) {
        if (recipient == null || messageId == null) {
            return;
        }
        boolean added = pending.computeIfAbsent(recipient, k -> new LinkedHashSet<>())
                .add(messageId);
        if (added) {
            appendToFile(recipient, messageId);
        }
    }

    /**
     * Снимок id, недоставленных пользователю {@code nick}, в порядке поступления.
     * Безопасен для перебора вне блокировки.
     */
    public synchronized Set<String> pendingFor(String nick) {
        LinkedHashSet<String> ids = pending.get(nick);
        return ids == null ? Set.of() : new LinkedHashSet<>(ids);
    }

    /** Очищает очередь пользователя {@code nick} (всё доставлено при входе). */
    public synchronized void clear(String nick) {
        if (pending.remove(nick) != null) {
            rewriteLocked();
        }
    }

    /** Сколько сообщений ждёт доставки пользователю (для тестов/диагностики). */
    public synchronized int pendingCount(String nick) {
        LinkedHashSet<String> ids = pending.get(nick);
        return ids == null ? 0 : ids.size();
    }

    // ---- внутреннее ----

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        int bad = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) {
                    bad++;
                    continue;
                }
                String recipient = line.substring(0, tab);
                String messageId = line.substring(tab + 1);
                pending.computeIfAbsent(recipient, k -> new LinkedHashSet<>()).add(messageId);
            }
        } catch (IOException e) {
            System.out.println("[offline] Не удалось прочитать очередь: " + e.getMessage());
        }
        int total = pending.values().stream().mapToInt(Set::size).sum();
        System.out.println("[offline] Загружено недоставленных: " + total
                + (bad > 0 ? " (пропущено битых: " + bad + ")" : ""));
    }

    private void appendToFile(String recipient, String messageId) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, recipient + '\t' + messageId + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[offline] Не удалось сохранить в очередь: " + e.getMessage());
        }
    }

    /** Полностью переписывает файл из памяти атомарно (через временный файл). */
    private void rewriteLocked() {
        List<String> lines = new ArrayList<>();
        for (var entry : pending.entrySet()) {
            for (String id : entry.getValue()) {
                lines.add(entry.getKey() + '\t' + id);
            }
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.out.println("[offline] Не удалось переписать очередь: " + e.getMessage());
        }
    }
}
