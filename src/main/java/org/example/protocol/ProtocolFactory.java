package org.example.protocol;

/**
 * Фабрика кодека протокола — единственная точка выбора формата.
 *
 * <p>Сейчас (ПР3) возвращает текстовый кодек. На ПР4 здесь будет возвращаться
 * {@code XmlProtocolCodec}, и этого достаточно, чтобы весь обмен перешёл на XML
 * — ни сетевой слой, ни сервер, ни клиент менять не придётся.
 */
public final class ProtocolFactory {

    private ProtocolFactory() { }

    public static ProtocolCodec createCodec() {
        return new TextProtocolCodec();
    }
}
