package org.example.client.model;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * UI-модель контакта (онлайн-пользователя) для списка слева.
 *
 * <p>Построена на JavaFX-property, чтобы {@code ListView} автоматически
 * обновлялся при смене ника или статуса — без ручной перерисовки.
 */
public class Contact {

    private final StringProperty nick = new SimpleStringProperty();
    private final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.ONLINE);

    public Contact(String nick, Status status) {
        this.nick.set(nick);
        this.status.set(status);
    }

    public StringProperty nickProperty()           { return nick; }
    public ObjectProperty<Status> statusProperty() { return status; }

    public String getNick()        { return nick.get(); }
    public void setNick(String n)  { nick.set(n); }

    public Status getStatus()           { return status.get(); }
    public void setStatus(Status s)     { status.set(s); }
}
