package org.example.protocol;

/**
 * Кодек протокола: превращает {@link Message} в строку для передачи по сети
 * и обратно. Это точка подмены формата.
 *
 * <p>На ПР3 можно использовать простую текстовую реализацию, а на ПР4 —
 * заменить её на {@code XmlProtocolCodec} (JAXP/DOM), не трогая ни сетевой
 * слой, ни GUI. Паттерн «Стратегия»: формат протокола — это сменная стратегия.
 */
public interface ProtocolCodec {

    /** Сериализует сообщение в строку (одна строка = одно сообщение). */
    String encode(Message message) throws ProtocolException;

    /** Разбирает строку обратно в объект {@link Message}. */
    Message decode(String raw) throws ProtocolException;
}
