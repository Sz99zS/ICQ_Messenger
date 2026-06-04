package org.example.client.model;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.example.protocol.Message;

/**
 * UI-модель контакта (онлайн-пользователя) для списка слева.
 *
 * <p>Построена на JavaFX-property, чтобы {@code ListView} автоматически
 * обновлялся при смене ника, статуса или числа непрочитанных — без ручной
 * перерисовки.
 *
 * <p>На ПР6 контакт стал «адресом диалога»: его {@link #getNick()} служит
 * ключом маршрутизации (личке — реальный ник, общему чату — псевдо-адрес
 * {@link Message#BROADCAST}), а {@link #getUnread()} — счётчиком непрочитанных
 * для бейджа.
 */
public class Contact {

    private final StringProperty nick = new SimpleStringProperty();
    private final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.ONLINE);
    private final IntegerProperty unread = new SimpleIntegerProperty(0);

    /** Псевдо-контакт «Общий чат» (broadcast), а не реальный пользователь. */
    private final boolean broadcast;

    public Contact(String nick, Status status) {
        this(nick, status, false);
    }

    private Contact(String nick, Status status, boolean broadcast) {
        this.nick.set(nick);
        this.status.set(status);
        this.broadcast = broadcast;
    }

    /** Создаёт псевдо-контакт «Общий чат» — постоянную верхнюю строку списка. */
    public static Contact broadcast() {
        return new Contact(Message.BROADCAST, Status.ONLINE, true);
    }

    public StringProperty nickProperty()           { return nick; }
    public ObjectProperty<Status> statusProperty() { return status; }
    public IntegerProperty unreadProperty()        { return unread; }

    public String getNick()        { return nick.get(); }
    public void setNick(String n)  { nick.set(n); }

    public Status getStatus()           { return status.get(); }
    public void setStatus(Status s)     { status.set(s); }

    public int getUnread()              { return unread.get(); }
    public void setUnread(int n)        { unread.set(n); }
    public void incrementUnread()       { unread.set(unread.get() + 1); }

    public boolean isBroadcast()        { return broadcast; }
}
