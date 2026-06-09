package org.example.server.store;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Хранилище переданных файлов (ПР15): байты на диске, метаданные в памяти.
 *
 * <p>Передача файла разделена на «загрузку байтов» и «сообщение-ссылку».
 * Загрузка идёт чанками в {@link UploadSession}, байты складываются в
 * {@code data/files/<fileId>}. Само сообщение-ссылка — обычное {@code MESSAGE}
 * с атрибутом {@code fileId}, поэтому маршрутизация, оффлайн-очередь (ПР13),
 * история и галочки (ПР14) работают для файлов даром.
 *
 * <p><b>fileId — случайный токен</b> ({@link SecureRandom}, 16 байт hex): он же
 * безопасное имя файла (ни обхода путей, ни коллизий), и его нельзя подобрать
 * перебором. Метаданные (имя/размер/mime) дублируются в индекс
 * {@code data/files/meta.log}, чтобы переживать перезапуск: одна строка =
 * {@code fileId \t имя \t размер \t mime}.
 *
 * <p>Лимит {@link #MAX_FILE_BYTES} защищает диск и память: и заявленный размер,
 * и фактически записанное проверяются. Класс потокобезопасен по монитору
 * {@code this}; запись в поток сессии идёт из одного потока-клиента.
 */
public final class FileStore {

    /** Каталог с файлами по умолчанию (рядом с прочими данными сервера). */
    public static final Path DEFAULT_DIR = Path.of("data", "files");

    /** Потолок размера одного файла — 10 МБ (ПР15). */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private final Path dir;
    private final Path metaFile;
    private final SecureRandom random = new SecureRandom();
    /** fileId → метаданные (под монитором {@code this}). */
    private final Map<String, FileMeta> files = new HashMap<>();

    /** Метаданные одного файла. */
    public record FileMeta(String name, long size, String mime) { }

    public FileStore(Path dir) {
        this.dir = dir;
        this.metaFile = dir.resolve("meta.log");
        load();
    }

    public FileStore() {
        this(DEFAULT_DIR);
    }

    /**
     * Открывает сессию загрузки. Проверяет заявленный размер против лимита и
     * резервирует случайный {@code fileId}.
     *
     * @throws IOException если размер превышает лимит или не удалось создать файл
     */
    public synchronized UploadSession beginUpload(String name, long declaredSize, String mime)
            throws IOException {
        if (declaredSize > MAX_FILE_BYTES) {
            throw new IOException("Файл больше лимита (" + (MAX_FILE_BYTES / 1024 / 1024) + " МБ)");
        }
        Files.createDirectories(dir);
        String fileId = newFileId();
        Path part = dir.resolve(fileId + ".part");
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(part,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
        return new UploadSession(fileId, part, out, safe(name), mime);
    }

    /** Метаданные файла или {@code null}, если такого нет. */
    public synchronized FileMeta meta(String fileId) {
        return files.get(fileId);
    }

    /** Путь к байтам файла (без проверки существования). */
    public Path path(String fileId) {
        return dir.resolve(fileId);
    }

    // ---- внутреннее ----

    /** Регистрирует завершённый файл: пишет метаданные в индекс. Под монитором. */
    private synchronized void register(String fileId, FileMeta m) {
        files.put(fileId, m);
        String line = String.join("\t", fileId, m.name(),
                Long.toString(m.size()), m.mime() == null ? "" : m.mime()) + System.lineSeparator();
        try {
            Files.writeString(metaFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[files] Не удалось записать метаданные: " + e.getMessage());
        }
    }

    private String newFileId() {
        byte[] buf = new byte[16];
        String id;
        do {
            random.nextBytes(buf);
            id = HexFormat.of().formatHex(buf);
        } while (files.containsKey(id) || Files.exists(dir.resolve(id)));
        return id;
    }

    /** Защита имени от переноса строк/табуляции (разделители индекса) и обхода путей. */
    private static String safe(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        String cleaned = name.replaceAll("[\\t\\r\\n/\\\\]", "_");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    private void load() {
        if (!Files.exists(metaFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(metaFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split("\t", -1);
                if (p.length >= 3) {
                    long size = 0;
                    try {
                        size = Long.parseLong(p[2]);
                    } catch (NumberFormatException ignored) { }
                    files.put(p[0], new FileMeta(p[1], size, p.length > 3 ? p[3] : null));
                }
            }
        } catch (IOException e) {
            System.out.println("[files] Не удалось прочитать индекс файлов: " + e.getMessage());
        }
        System.out.println("[files] Загружено файлов: " + files.size());
    }

    /**
     * Сессия загрузки одного файла. Живёт в потоке клиента-отправителя, пишет
     * чанки на диск и контролирует фактический размер. По {@link #finish()}
     * атомарно переименовывает {@code .part} в финальный файл и регистрирует
     * метаданные; по {@link #abort()} удаляет недописанное.
     */
    public final class UploadSession {
        private final String fileId;
        private final Path part;
        private final OutputStream out;
        private final String name;
        private final String mime;
        private long written;
        /** Адресат сообщения-ссылки — заполняет обработчик из FILE_START. */
        private String recipient;

        private UploadSession(String fileId, Path part, OutputStream out, String name, String mime) {
            this.fileId = fileId;
            this.part = part;
            this.out = out;
            this.name = name;
            this.mime = mime;
        }

        public String fileId()            { return fileId; }
        public String name()              { return name; }
        public String mime()              { return mime; }
        public long written()             { return written; }
        public String recipient()         { return recipient; }
        public void setRecipient(String r) { this.recipient = r; }

        /** Дописывает чанк; следит, чтобы суммарный размер не превысил лимит. */
        public void write(byte[] data) throws IOException {
            if (written + data.length > MAX_FILE_BYTES) {
                throw new IOException("Превышен лимит размера файла");
            }
            out.write(data);
            written += data.length;
        }

        /** Завершает загрузку: фиксирует файл и метаданные. */
        public void finish() throws IOException {
            out.close();
            Path target = dir.resolve(fileId);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            register(fileId, new FileMeta(name, written, mime));
        }

        /** Прерывает загрузку: закрывает поток и удаляет недописанный файл. */
        public void abort() {
            try {
                out.close();
            } catch (IOException ignored) { }
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) { }
        }
    }
}
