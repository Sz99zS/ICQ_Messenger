package org.example.client.controller;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.util.Duration;
import org.example.client.model.ChatMessage;
import org.example.client.model.Contact;
import org.example.client.model.Status;
import org.example.client.model.UserPresence;
import org.example.client.service.ClientService;
import org.example.client.service.ClientServiceListener;
import org.example.client.ui.ThemeManager;
import org.example.protocol.Message;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Контроллер главного окна чата.
 *
 * <p>Реализует {@link ClientServiceListener}: всё, что приходит «снизу» от
 * сервиса, попадает сюда и отображается. Обновления UI обёрнуты в
 * {@code Platform.runLater}, потому что сервис зовёт эти методы из сетевого
 * потока.
 *
 * <p>На ПР6 чат стал многодиалоговым: слева — «Общий чат» (broadcast) и личные
 * собеседники, у каждого своя история сообщений. Выбор контакта переключает
 * ленту и адресует отправку: общему чату — {@link Message#BROADCAST}, личке —
 * ник собеседника (сервер уже умеет доставлять адресно через {@code sendTo}).
 */
public class MainChatController implements ClientServiceListener {

    @FXML private ListView<Contact> contactList;
    @FXML private ListView<ChatMessage> messageList;
    @FXML private TextField inputField;
    @FXML private Label titleLabel;
    @FXML private Label typingLabel;

    private ClientService service;
    private String nick;

    /** История по каждому диалогу. Ключ — {@link Contact#getNick()} собеседника. */
    private final Map<String, ObservableList<ChatMessage>> conversations = new HashMap<>();
    /** Контакты по нику — чтобы переиспользовать объекты при перестроении списка. */
    private final Map<String, Contact> contactsByNick = new HashMap<>();
    private final ObservableList<Contact> contacts = FXCollections.observableArrayList();

    /** Постоянная верхняя строка списка — общий чат. */
    private Contact broadcastContact;
    /** Контакт, диалог с которым открыт сейчас. */
    private Contact activeContact;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // --- индикатор «печатает…» ---
    /** Ники собеседников, которые сейчас печатают (для строки над полем ввода). */
    private final Set<String> typingNicks = new LinkedHashSet<>();
    /** Отправлен ли уже на сервер сигнал «я печатаю» (чтобы не слать на каждый символ). */
    private boolean typingSent;
    /** После паузы в наборе шлём «перестал печатать». */
    private PauseTransition typingStopTimer;
    /** Страховка: гасим чужой индикатор, если «стоп» от собеседника потерялся. */
    private PauseTransition typingClearTimer;

    /** Внедрение зависимостей после загрузки FXML. */
    public void init(ClientService service, String nick) {
        this.service = service;
        this.nick = nick;
        this.service.setListener(this);

        titleLabel.setText("Вы вошли как: " + nick);

        messageList.setCellFactory(lv -> new ChatBubbleCell());

        contactList.setItems(contacts);
        contactList.setCellFactory(lv -> new ContactCell());
        // Общий чат всегда присутствует и выбран по умолчанию.
        broadcastContact = Contact.broadcast();
        contacts.add(broadcastContact);
        contactList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, sel) -> onContactSelected(sel));
        contactList.getSelectionModel().select(broadcastContact);

        typingLabel.setText("");
        typingLabel.setVisible(false);
        // Место под строкой резервируется только когда она видима — без скачков layout.
        typingLabel.managedProperty().bind(typingLabel.visibleProperty());
        setupTypingTimers();
        // Набор текста → шлём «печатает…» (с троттлингом, см. onInputChanged).
        inputField.textProperty().addListener((obs, old, val) -> onInputChanged(val));
    }

    /** Возвращает (создавая при необходимости) историю диалога по ключу. */
    private ObservableList<ChatMessage> conversationFor(String key) {
        return conversations.computeIfAbsent(key, k -> FXCollections.observableArrayList());
    }

    /**
     * ПР10: закрепляет собеседника в списке контактов, если его там ещё нет.
     * Нужен, чтобы личный диалог не пропадал, когда собеседник оффлайн (нет в
     * USER_LIST) — например, при проигрывании истории при входе. Новичок
     * добавляется как OFFLINE; статус ему уточнит ближайший USER_LIST.
     */
    private void ensureContact(String partner) {
        if (partner == null || partner.equals(nick) || Message.BROADCAST.equals(partner)
                || contactsByNick.containsKey(partner)) {
            return;
        }
        Contact c = new Contact(partner, Status.OFFLINE);
        contactsByNick.put(partner, c);
        contacts.add(c);
    }

    /** Переключение открытого диалога при выборе контакта в списке. */
    private void onContactSelected(Contact sel) {
        if (sel == null) {
            return;
        }
        activeContact = sel;
        sel.setUnread(0); // открыли диалог — непрочитанных больше нет
        contactList.refresh();
        ObservableList<ChatMessage> conv = conversationFor(sel.getNick());
        messageList.setItems(conv);
        if (!conv.isEmpty()) {
            messageList.scrollTo(conv.size() - 1);
        }
        updateTitle();
    }

    /** Заголовок окна отражает, с кем сейчас разговор. */
    private void updateTitle() {
        String where = activeContact == null || activeContact.isBroadcast()
                ? "Общий чат"
                : "Личный чат с " + activeContact.getNick();
        titleLabel.setText(nick + " — " + where);
    }

    /** Создаёт таймеры троттлинга/страховки (на потоке FX, после загрузки FXML). */
    private void setupTypingTimers() {
        typingStopTimer = new PauseTransition(Duration.seconds(2));
        typingStopTimer.setOnFinished(e -> stopTyping());

        typingClearTimer = new PauseTransition(Duration.seconds(3));
        typingClearTimer.setOnFinished(e -> {
            typingNicks.clear();
            updateTypingLabel();
        });
    }

    /** Реакция на изменение текста в поле ввода: сообщаем серверу о наборе. */
    private void onInputChanged(String text) {
        if (text == null || text.isEmpty()) {
            stopTyping();
            return;
        }
        if (!typingSent) {
            service.sendTyping(true);
            typingSent = true;
        }
        typingStopTimer.playFromStart(); // перезапускаем отсчёт паузы
    }

    /** Сообщает серверу, что мы перестали печатать (если до этого печатали). */
    private void stopTyping() {
        typingStopTimer.stop();
        if (typingSent) {
            service.sendTyping(false);
            typingSent = false;
        }
    }

    @FXML
    private void onSend() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        String target = activeContact == null ? Message.BROADCAST : activeContact.getNick();
        // Своё сообщение сразу в ленту текущего диалога (справа).
        ObservableList<ChatMessage> conv = conversationFor(target);
        conv.add(new ChatMessage(nick, text, LocalTime.now().format(TIME_FMT), true));
        messageList.scrollTo(conv.size() - 1);

        service.sendMessage(target, text);
        inputField.clear();
        stopTyping(); // отправили — больше не «печатаем»
    }

    @FXML
    private void onToggleTheme() {
        ThemeManager.getInstance().toggle(titleLabel.getScene());
    }

    // ---- ClientServiceListener: события «снизу» ----

    @Override
    public void onConnected() {
        Platform.runLater(this::updateTitle);
    }

    @Override
    public void onMessage(Message message) {
        Platform.runLater(() -> {
            boolean mine = nick.equals(message.getFrom());
            // Broadcast → общий чат; личка → диалог с собеседником (для своих же
            // сообщений из истории собеседник — это адресат, а не отправитель).
            String key = message.isBroadcast()
                    ? Message.BROADCAST
                    : (mine ? message.getTo() : message.getFrom());
            // Метка истории (ПР9): такие сообщения проигрываются при входе и не
            // должны поднимать счётчик «непрочитано».
            boolean history = "1".equals(message.getAttributes().get("hist"));

            // ПР10: личный диалог закрепляет собеседника в списке контактов
            // сразу (в т.ч. при проигрывании истории с тем, кто сейчас оффлайн).
            if (!message.isBroadcast()) {
                ensureContact(key);
            }

            ObservableList<ChatMessage> conv = conversationFor(key);
            conv.add(new ChatMessage(
                    message.getFrom(),
                    message.getBody(),
                    formatTime(message.getTimestamp()),
                    mine));

            boolean isActive = activeContact != null && activeContact.getNick().equals(key);
            if (isActive) {
                messageList.scrollTo(conv.size() - 1);
            } else if (!history) {
                Contact c = Message.BROADCAST.equals(key) ? broadcastContact : contactsByNick.get(key);
                if (c != null) {
                    c.incrementUnread();
                    contactList.refresh();
                }
            }
        });
    }

    /** Метка времени сообщения (epoch ms) → {@code HH:mm} в локальной зоне. */
    private static String formatTime(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(TIME_FMT);
    }

    @Override
    public void onUserListChanged(List<UserPresence> users) {
        Platform.runLater(() -> {
            // Перестраиваем список, переиспользуя существующие Contact, чтобы
            // сохранить счётчики непрочитанных у тех, кто остался онлайн.
            Map<String, Contact> previous = new HashMap<>(contactsByNick);
            contactsByNick.clear();
            contacts.setAll(broadcastContact); // общий чат всегда первый
            for (UserPresence u : users) {
                if (u.nick().equals(nick)) {
                    continue; // себя в контактах не показываем
                }
                Contact c = previous.get(u.nick());
                if (c == null) {
                    c = new Contact(u.nick(), u.status());
                }
                c.setStatus(u.status());
                contactsByNick.put(u.nick(), c);
                contacts.add(c);
            }
            // ПР10: контакты с непустой историей остаются в списке даже когда
            // собеседник оффлайн (его нет в USER_LIST) — иначе личный диалог
            // исчезал бы при выходе собеседника. Счётчик непрочитанных при этом
            // сохраняется (переиспользуем прежний Contact).
            for (String key : conversations.keySet()) {
                if (Message.BROADCAST.equals(key) || key.equals(nick)
                        || contactsByNick.containsKey(key) || conversationFor(key).isEmpty()) {
                    continue;
                }
                Contact c = previous.get(key);
                if (c == null) {
                    c = new Contact(key, Status.OFFLINE);
                }
                c.setStatus(Status.OFFLINE);
                contactsByNick.put(key, c);
                contacts.add(c);
            }
            // Восстанавливаем открытый диалог: тот же контакт, иначе общий чат.
            Contact toSelect = broadcastContact;
            if (activeContact != null && !activeContact.isBroadcast()) {
                Contact still = contactsByNick.get(activeContact.getNick());
                if (still != null) {
                    toSelect = still;
                }
            } else if (activeContact != null && activeContact.isBroadcast()) {
                toSelect = broadcastContact;
            }
            contactList.getSelectionModel().select(toSelect);
        });
    }

    @Override
    public void onTyping(String who, boolean typing) {
        Platform.runLater(() -> {
            if (who == null || who.equals(nick)) {
                return; // свой же набор не показываем
            }
            if (typing) {
                typingNicks.add(who);
                typingClearTimer.playFromStart(); // страховка от потерянного «стоп»
            } else {
                typingNicks.remove(who);
            }
            updateTypingLabel();
        });
    }

    /** Перерисовывает строку «X печатает…» под лентой сообщений. */
    private void updateTypingLabel() {
        if (typingNicks.isEmpty()) {
            typingLabel.setText("");
            typingLabel.setVisible(false);
            return;
        }
        String who = String.join(", ", typingNicks);
        typingLabel.setText(who + (typingNicks.size() == 1 ? " печатает…" : " печатают…"));
        typingLabel.setVisible(true);
    }

    @Override
    public void onDisconnected() {
        Platform.runLater(() -> titleLabel.setText("Соединение разорвано"));
    }

    @Override
    public void onError(String reason) {
        Platform.runLater(() -> titleLabel.setText("Ошибка: " + reason));
    }

    /** Ячейка контакта: ник + цветной кружок статуса + бейдж непрочитанных. */
    private static class ContactCell extends ListCell<Contact> {
        @Override
        protected void updateItem(Contact item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("status-online", "status-away", "status-offline", "contact-broadcast");
            if (empty || item == null) {
                setText(null);
                return;
            }
            String label = item.isBroadcast() ? "# Общий чат" : item.getNick();
            if (item.getUnread() > 0) {
                label += "  (" + item.getUnread() + ")";
            }
            setText(label);
            if (item.isBroadcast()) {
                getStyleClass().add("contact-broadcast");
            } else {
                getStyleClass().add(switch (item.getStatus()) {
                    case ONLINE -> "status-online";
                    case AWAY -> "status-away";
                    case OFFLINE -> "status-offline";
                });
            }
        }
    }
}
