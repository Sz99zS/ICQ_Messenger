package org.example.server.store;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Хранилище учётных записей (ПР12): регистрация и проверка пароля.
 *
 * <p>Делает «личность» в мессенджере устойчивой — ник принадлежит тому, кто
 * знает пароль, а не первому занявшему. Это фундамент для последующих фич
 * (галочки «прочитано», нормальная оффлайн-доставка): без устойчивого аккаунта
 * они шатки.
 *
 * <p><b>Хэширование.</b> Пароли не хранятся в открытом виде. Используется
 * <b>PBKDF2WithHmacSHA256</b> со случайной солью на каждую учётку и десятками
 * тысяч итераций — индустриальный стандарт (рекомендация OWASP). Алгоритм
 * намеренно медленный, что делает перебор украденного файла дорогим. Всё —
 * штатными средствами JDK, без сторонних библиотек.
 *
 * <p><b>Формат файла.</b> По аналогии с {@link MessageStore}: текстовый
 * журнал, одна строка = одна учётка, поля через табуляцию:
 * <pre>{@code ник \t итерации \t соль(Base64) \t хэш(Base64) \t созданоMs}</pre>
 * Табуляция как разделитель безопасна: ник валидируется и не может её
 * содержать. Файл дописывается при регистрации (append) и целиком читается при
 * старте сервера.
 *
 * <p>Класс потокобезопасен: регистрация и проверка сериализованы по монитору
 * {@code this}. Учётки держатся в памяти — проверка пароля не читает файл.
 */
public final class AccountStore {

    /** Путь к файлу учёток по умолчанию (рядом с журналом переписки). */
    public static final Path DEFAULT_FILE = Path.of("data", "users.log");

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final Path file;
    private final SecureRandom random = new SecureRandom();
    /** Учётки в памяти: ник → запись (под монитором {@code this}). */
    private final Map<String, Account> accounts = new HashMap<>();

    /** Одна учётная запись: параметры хэша пароля. */
    private record Account(int iterations, byte[] salt, byte[] hash, long createdAt) { }

    public AccountStore(Path file) {
        this.file = file;
        load();
    }

    public AccountStore() {
        this(DEFAULT_FILE);
    }

    /**
     * Регистрирует новую учётку. Ник должен быть валиден (см.
     * {@link #validate}) и ещё не занят. Пароль хэшируется и дописывается в файл.
     *
     * @return {@code null} при успехе, иначе текст ошибки для клиента
     */
    public synchronized String register(String nick, String password) {
        String invalid = validate(nick, password);
        if (invalid != null) {
            return invalid;
        }
        if (accounts.containsKey(nick)) {
            return "Ник уже занят";
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash;
        try {
            hash = pbkdf2(password, salt, ITERATIONS);
        } catch (GeneralSecurityException e) {
            System.out.println("[accounts] Не удалось захэшировать пароль: " + e.getMessage());
            return "Внутренняя ошибка сервера";
        }
        Account account = new Account(ITERATIONS, salt, hash, System.currentTimeMillis());
        accounts.put(nick, account);
        appendToFile(nick, account);
        System.out.println("[accounts] Зарегистрирован: " + nick + " (всего учёток: "
                + accounts.size() + ")");
        return null;
    }

    /**
     * Проверяет пару ник/пароль.
     *
     * @return {@code true}, если учётка есть и пароль совпал
     */
    public synchronized boolean verify(String nick, String password) {
        Account account = accounts.get(nick);
        if (account == null || password == null) {
            return false;
        }
        try {
            byte[] candidate = pbkdf2(password, account.salt(), account.iterations());
            return constantTimeEquals(candidate, account.hash());
        } catch (GeneralSecurityException e) {
            System.out.println("[accounts] Ошибка проверки пароля: " + e.getMessage());
            return false;
        }
    }

    /** Есть ли уже такая учётка (для диагностики/будущих фич). */
    public synchronized boolean exists(String nick) {
        return accounts.containsKey(nick);
    }

    // ---- внутреннее ----

    /** Проверяет допустимость ника и пароля. Возвращает текст ошибки или {@code null}. */
    private static String validate(String nick, String password) {
        if (nick == null || nick.isBlank()) {
            return "Пустой ник";
        }
        if (nick.length() > 32) {
            return "Слишком длинный ник (максимум 32 символа)";
        }
        // Табуляция — разделитель полей в файле; перевод строки разорвал бы запись.
        if (nick.indexOf('\t') >= 0 || nick.indexOf('\n') >= 0 || nick.indexOf('\r') >= 0) {
            return "Ник содержит недопустимые символы";
        }
        if (password == null || password.length() < 4) {
            return "Пароль слишком короткий (минимум 4 символа)";
        }
        return null;
    }

    private byte[] pbkdf2(String password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            try {
                return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new GeneralSecurityException(e);
        }
    }

    /** Сравнение байтов за постоянное время — не утекает совпавший префикс по таймингу. */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

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
                String[] parts = line.split("\t", -1);
                if (parts.length != 5) {
                    bad++;
                    continue;
                }
                try {
                    String nick = parts[0];
                    int iterations = Integer.parseInt(parts[1]);
                    byte[] salt = Base64.getDecoder().decode(parts[2]);
                    byte[] hash = Base64.getDecoder().decode(parts[3]);
                    long createdAt = Long.parseLong(parts[4]);
                    accounts.put(nick, new Account(iterations, salt, hash, createdAt));
                } catch (RuntimeException e) {
                    bad++;
                }
            }
        } catch (Exception e) {
            System.out.println("[accounts] Не удалось прочитать учётки: " + e.getMessage());
        }
        System.out.println("[accounts] Загружено учёток: " + accounts.size()
                + (bad > 0 ? " (пропущено битых: " + bad + ")" : ""));
    }

    private void appendToFile(String nick, Account account) {
        String line = String.join("\t",
                nick,
                Integer.toString(account.iterations()),
                Base64.getEncoder().encodeToString(account.salt()),
                Base64.getEncoder().encodeToString(account.hash()),
                Long.toString(account.createdAt())) + System.lineSeparator();
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.out.println("[accounts] Не удалось сохранить учётку " + nick
                    + ": " + e.getMessage());
        }
    }

    /** Узкое внутреннее исключение, чтобы не светить наружу типы JCA. */
    private static final class GeneralSecurityException extends Exception {
        GeneralSecurityException(Throwable cause) { super(cause); }
    }
}
