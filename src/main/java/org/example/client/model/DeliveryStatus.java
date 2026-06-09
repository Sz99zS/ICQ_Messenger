package org.example.client.model;

/**
 * Статус доставки исходящего личного сообщения (ПР14) — то, что рисуется
 * значком на «своём» пузыре в ленте.
 *
 * <p>Жизненный путь: {@link #PENDING} (адресат оффлайн, сообщение в очереди) →
 * {@link #DELIVERED} (дошло) → {@link #READ} (адресат открыл диалог). Статус
 * только растёт и не откатывается.
 */
public enum DeliveryStatus {

    /** Ждёт доставки — адресат не в сети. */
    PENDING("⏳"),
    /** Доставлено адресату. */
    DELIVERED("✓"),
    /** Прочитано адресатом. */
    READ("✓✓");

    private final String glyph;

    DeliveryStatus(String glyph) {
        this.glyph = glyph;
    }

    /** Значок для отрисовки в пузыре. */
    public String glyph() {
        return glyph;
    }
}
