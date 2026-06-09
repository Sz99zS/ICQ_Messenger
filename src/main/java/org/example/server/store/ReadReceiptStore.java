package org.example.server.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Множество id прочитанных личных сообщений (ПР14).
 *
 * <p>Делает галочку «прочитано» (✓✓) устойчивой к перезапуску: когда адресат
 * открывает диалог, id всех сообщений отправителя помечаются прочитанными здесь.
 * При следующем входе отправителя сервер по этому множеству восстанавливает
 * статус его исходящих пузырей (см. {@code ClientHandler#sendHistory}).
 *
 * <p>«Прочитано» — терминальное состояние, поэтому файл только дописывается
 * (append): одна строка = один id. Дубликаты при загрузке схлопывает множество в
 * памяти. Файл может пережить ротацию журнала переписки и хранить id уже
 * выпавших сообщений — это безвредно (лишний id ни на что не влияет); при
 * необходимости его можно подчистить отдельной фоновой компакцией.
 *
 * <p>Класс потокобезопасен: всё сериализовано по монитору {@code this}; проверка
 * статуса идёт из памяти и файл не читает.
 */
public final class ReadReceiptStore {

    /** Путь по умолчанию (рядом с прочими журналами сервера). */
    public static final Path DEFAULT_FILE = Path.of("data", "read.log");

    private final Path file;
    /** id прочитанных сообщений (под монитором {@code this}). */
    private final Set<String> read = new HashSet<>();

    public ReadReceiptStore(Path file) {
        this.file = file;
        load();
    }

    public ReadReceiptStore() {
        this(DEFAULT_FILE);
    }

    /** Помечает прочитанными переданные id; новые — дописывает в файл. */
    public synchronized void markRead(Collection<String> ids) {
        StringBuilder toAppend = new StringBuilder();
        for (String id : ids) {
            if (id != null && read.add(id)) {
                toAppend.append(id).append(System.lineSeparator());
            }
        }
        if (toAppend.length() > 0) {
            appendToFile(toAppend.toString());
        }
    }

    /** Прочитано ли сообщение с этим id. */
    public synchronized boolean isRead(String id) {
        return id != null && read.contains(id);
    }

    // ---- внутреннее ----

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    read.add(line.trim());
                }
            }
        } catch (IOException e) {
            System.out.println("[read] Не удалось прочитать квитанции: " + e.getMessage());
        }
        System.out.println("[read] Загружено прочитанных: " + read.size());
    }

    private void appendToFile(String lines) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[read] Не удалось сохранить квитанции: " + e.getMessage());
        }
    }
}
