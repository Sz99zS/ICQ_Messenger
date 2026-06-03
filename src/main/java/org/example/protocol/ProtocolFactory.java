package org.example.protocol;

/**
 * Фабрика кодека протокола — единственная точка выбора формата.
 *
 * <p>На ПР4 обмен переведён на XML ({@link XmlProtocolCodec}): достаточно было
 * сменить возвращаемое значение здесь — ни сетевой слой, ни сервер, ни клиент
 * менять не пришлось. Текстовый кодек {@link TextProtocolCodec} оставлен как
 * запасная стратегия (и удобен для отладки/тестов).
 */
public final class ProtocolFactory {

    private ProtocolFactory() { }

    public static ProtocolCodec createCodec() {
        return new XmlProtocolCodec();
    }
}
