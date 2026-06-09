package org.example.server.store;

import org.example.protocol.Message;

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
 * Персистентный реестр групповых комнат (ПР16): комната → её участники.
 *
 * <p>Делает членство в комнате устойчивым к перезапуску сервера и к перелогину
 * клиента: тот, кто вступил в комнату, остаётся её участником, пока сам не
 * выйдет. Благодаря этому доставка в комнату, история и оффлайн-очередь
 * переиспользуют уже готовую машинерию (ПР9/ПР13), как и личка.
 *
 * <p><b>Адресация.</b> Имя комнаты включает префикс {@link Message#ROOM_PREFIX}
 * («#»), то есть совпадает с тем, что едет в поле {@code to} сообщения — никаких
 * конверсий между «ключом» и «адресом». Комната существует, пока в ней есть хотя
 * бы один участник; когда выходит последний — комната исчезает.
 *
 * <p><b>Формат файла.</b> По аналогии с {@link OfflineStore}: текстовый журнал,
 * одна строка = одна пара через табуляцию:
 * <pre>{@code #комната \t ник}</pre>
 * Вступление — дешёвый append; выход целиком переписывает файл без строк этой
 * пары. Имя комнаты и ник валидируются и не содержат табуляции/переводов строк.
 *
 * <p>Класс потокобезопасен: всё сериализовано по монитору {@code this}. Состав
 * комнат держится в памяти — маршрутизация не читает файл.
 */
public final class RoomStore {

    /** Путь по умолчанию (рядом с журналом переписки и очередью оффлайна). */
    public static final Path DEFAULT_FILE = Path.of("data", "rooms.log");

    /** Максимальная длина имени комнаты (вместе с префиксом «#»). */
    private static final int MAX_NAME = 32;

    private final Path file;
    /** Комната → её участники (порядок вступления). Под монитором {@code this}. */
    private final Map<String, LinkedHashSet<String>> rooms = new LinkedHashMap<>();

    public RoomStore(Path file) {
        this.file = file;
        load();
    }

    public RoomStore() {
        this(DEFAULT_FILE);
    }

    /**
     * Проверяет допустимость имени комнаты. Имя должно начинаться с «#», иметь
     * непустую содержательную часть и не содержать служебных символов
     * (разделители файла/протокола/списков).
     *
     * @return {@code null} если имя валидно, иначе текст ошибки для клиента
     */
    public static String validateName(String room) {
        if (room == null || !room.startsWith(Message.ROOM_PREFIX)) {
            return "Имя комнаты должно начинаться с '#'";
        }
        String body = room.substring(Message.ROOM_PREFIX.length());
        if (body.isBlank()) {
            return "Пустое имя комнаты";
        }
        if (room.length() > MAX_NAME) {
            return "Слишком длинное имя комнаты (максимум " + MAX_NAME + " символов)";
        }
        // '\t'/'\n'/'\r' рвут запись в файле; ','/':' — разделители в списках протокола
        // (ROOM_LIST/ROOM_MEMBERS/USER_LIST), '#' допустим только как ведущий префикс.
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r' || c == ',' || c == ':'
                    || c == Message.ROOM_PREFIX.charAt(0)) {
                return "Имя комнаты содержит недопустимые символы";
            }
        }
        return null;
    }

    /**
     * Вступает {@code nick} в комнату {@code room} (комната создаётся, если её
     * ещё не было). Повторное вступление безвредно.
     *
     * @return {@code true}, если комнаты не существовало и она была создана
     */
    public synchronized boolean join(String room, String nick) {
        if (room == null || nick == null) {
            return false;
        }
        boolean created = !rooms.containsKey(room);
        boolean added = rooms.computeIfAbsent(room, k -> new LinkedHashSet<>()).add(nick);
        if (added) {
            appendToFile(room, nick);
        }
        return created;
    }

    /**
     * Выводит {@code nick} из комнаты {@code room}. Если он был последним
     * участником — комната удаляется.
     *
     * @return {@code true}, если состав изменился
     */
    public synchronized boolean leave(String room, String nick) {
        LinkedHashSet<String> members = rooms.get(room);
        if (members == null || !members.remove(nick)) {
            return false;
        }
        if (members.isEmpty()) {
            rooms.remove(room);
        }
        rewriteLocked();
        return true;
    }

    /** Снимок участников комнаты (порядок вступления). Безопасен вне блокировки. */
    public synchronized Set<String> membersOf(String room) {
        LinkedHashSet<String> members = rooms.get(room);
        return members == null ? Set.of() : new LinkedHashSet<>(members);
    }

    /** Состоит ли {@code nick} в комнате {@code room}. */
    public synchronized boolean isMember(String room, String nick) {
        LinkedHashSet<String> members = rooms.get(room);
        return members != null && members.contains(nick);
    }

    /** Существует ли комната (есть хотя бы один участник). */
    public synchronized boolean exists(String room) {
        return rooms.containsKey(room);
    }

    /** Снимок имён всех существующих комнат. */
    public synchronized Set<String> allRooms() {
        return new LinkedHashSet<>(rooms.keySet());
    }

    /** Комнаты, в которых состоит {@code nick} (для проигрывания истории при входе). */
    public synchronized Set<String> roomsOf(String nick) {
        Set<String> result = new LinkedHashSet<>();
        for (var entry : rooms.entrySet()) {
            if (entry.getValue().contains(nick)) {
                result.add(entry.getKey());
            }
        }
        return result;
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
                String room = line.substring(0, tab);
                String nick = line.substring(tab + 1);
                rooms.computeIfAbsent(room, k -> new LinkedHashSet<>()).add(nick);
            }
        } catch (IOException e) {
            System.out.println("[rooms] Не удалось прочитать комнаты: " + e.getMessage());
        }
        int members = rooms.values().stream().mapToInt(Set::size).sum();
        System.out.println("[rooms] Загружено комнат: " + rooms.size() + ", участников: " + members
                + (bad > 0 ? " (пропущено битых: " + bad + ")" : ""));
    }

    private void appendToFile(String room, String nick) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, room + '\t' + nick + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[rooms] Не удалось сохранить состав комнаты: " + e.getMessage());
        }
    }

    /** Полностью переписывает файл из памяти атомарно (через временный файл). */
    private void rewriteLocked() {
        List<String> lines = new ArrayList<>();
        for (var entry : rooms.entrySet()) {
            for (String nick : entry.getValue()) {
                lines.add(entry.getKey() + '\t' + nick);
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
            System.out.println("[rooms] Не удалось переписать комнаты: " + e.getMessage());
        }
    }
}
