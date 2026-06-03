package org.example.protocol;

/**
 * Простой текстовый кодек протокола (одна строка = одно сообщение).
 *
 * <p>Формат: {@code TYPE \t from \t to \t timestamp \t body}, где спецсимволы
 * в полях экранируются. Это временная реализация для ПР3 — на ПР4 её заменит
 * {@code XmlProtocolCodec}, при этом сетевой слой и GUI не изменятся
 * (см. {@link ProtocolFactory}).
 */
public class TextProtocolCodec implements ProtocolCodec {

    private static final char SEP = '\t';

    @Override
    public String encode(Message m) {
        long ts = m.getTimestamp();
        return m.getType().name()
                + SEP + esc(nullToEmpty(m.getFrom()))
                + SEP + esc(nullToEmpty(m.getTo()))
                + SEP + ts
                + SEP + esc(nullToEmpty(m.getBody()));
    }

    @Override
    public Message decode(String raw) throws ProtocolException {
        if (raw == null) {
            throw new ProtocolException("Пустая строка протокола");
        }
        // -1 сохраняет хвостовые пустые поля.
        String[] parts = raw.split("\t", -1);
        if (parts.length != 5) {
            throw new ProtocolException("Ожидалось 5 полей, получено " + parts.length + ": " + raw);
        }
        MessageType type;
        try {
            type = MessageType.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Неизвестный тип сообщения: " + parts[0], e);
        }
        String from = emptyToNull(unesc(parts[1]));
        String to = emptyToNull(unesc(parts[2]));
        long ts;
        try {
            ts = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            throw new ProtocolException("Некорректная метка времени: " + parts[3], e);
        }
        String body = emptyToNull(unesc(parts[4]));
        return new Message(type, from, to, body, ts);
    }

    // ---- экранирование спецсимволов, чтобы поле не разорвало строку ----

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '\\' -> sb.append('\\');
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
    private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }
}
