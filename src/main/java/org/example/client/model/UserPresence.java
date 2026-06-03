package org.example.client.model;

/**
 * Присутствие пользователя в сети: ник + его {@link Status}.
 *
 * <p>Сервис разбирает строку {@code USER_LIST} формата {@code ник:СТАТУС,...}
 * в список таких записей и отдаёт их GUI. Так контроллеру не нужно знать про
 * формат протокола — он получает уже готовые пары «ник → статус».
 */
public record UserPresence(String nick, Status status) { }
