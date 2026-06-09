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
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.example.client.model.ChatMessage;
import org.example.client.model.Contact;
import org.example.client.model.DeliveryStatus;
import org.example.client.model.Status;
import org.example.client.model.UserPresence;
import org.example.client.service.ClientService;
import org.example.client.service.ClientServiceListener;
import org.example.client.ui.ThemeManager;
import org.example.protocol.Message;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
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
    /** Свои исходящие личные пузыри по id — чтобы обновлять галочку по квитанции (ПР14). */
    private final Map<String, ChatMessage> outgoingById = new HashMap<>();
    /** Счётчик для клиентских id исходящих сообщений (ник делает их уникальными в сети). */
    private int messageCounter;
    /** Файловые пузыри по серверному fileId — для подстановки скачанных байтов (ПР15). */
    private final Map<String, ChatMessage> fileById = new HashMap<>();
    /** fileId, ожидающие сохранения после докачки (клик «Сохранить» до прихода байтов). */
    private final Set<String> pendingSaves = new HashSet<>();

    /** Потолок размера файла на клиенте (зеркалит серверный лимит). */
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
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

        messageList.setCellFactory(lv -> new ChatBubbleCell(this));

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
        // ПР14: открыли личный диалог — сообщаем собеседнику, что прочли (✓✓ у него).
        if (!sel.isBroadcast()) {
            service.sendRead(sel.getNick());
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
        boolean broadcast = activeContact == null || activeContact.isBroadcast();
        String target = broadcast ? Message.BROADCAST : activeContact.getNick();
        String id = broadcast ? null : nextMessageId();
        // Своё сообщение сразу в ленту текущего диалога (справа). Личное стартует
        // со статусом «ждёт доставки» (⏳) — квитанции обновят галочку (ПР14).
        ChatMessage own = broadcast
                ? new ChatMessage(nick, text, LocalTime.now().format(TIME_FMT), true)
                : new ChatMessage(id, nick, text, LocalTime.now().format(TIME_FMT), true,
                        true, DeliveryStatus.PENDING);
        if (id != null) {
            outgoingById.put(id, own);
        }
        ObservableList<ChatMessage> conv = conversationFor(target);
        conv.add(own);
        messageList.scrollTo(conv.size() - 1);

        service.sendMessage(target, text, id);
        inputField.clear();
        stopTyping(); // отправили — больше не «печатаем»
    }

    @FXML
    private void onAttach() {
        if (service == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите файл для отправки");
        File file = chooser.showOpenDialog(window());
        if (file == null) {
            return;
        }
        if (file.length() > MAX_FILE_BYTES) {
            titleLabel.setText("Файл больше 10 МБ — не отправлен");
            return;
        }
        boolean broadcast = activeContact == null || activeContact.isBroadcast();
        String target = broadcast ? Message.BROADCAST : activeContact.getNick();
        String ref = nextMessageId();
        // Локальный пузырь сразу: у отправителя превью берётся из самого файла,
        // личный — со статусом «ждёт доставки» (галочки из ПР14).
        ChatMessage bubble = ChatMessage.fileMessage(broadcast ? null : ref, nick,
                LocalTime.now().format(TIME_FMT), true, !broadcast,
                broadcast ? null : DeliveryStatus.PENDING, file.getName(), file.length(),
                null, null, file);
        if (!broadcast) {
            outgoingById.put(ref, bubble);
        }
        ObservableList<ChatMessage> conv = conversationFor(target);
        conv.add(bubble);
        messageList.scrollTo(conv.size() - 1);
        service.uploadFile(target, file, ref);
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
            ChatMessage cm = toChatMessage(message, mine);
            conv.add(cm);
            // ПР14: свои личные пузыри помним по id — по ним прилетят галочки.
            if (cm.getId() != null) {
                outgoingById.put(cm.getId(), cm);
            }
            // ПР15: файловые пузыри помним по fileId — в них подставим скачанные байты.
            if (cm.isFile() && cm.getFileId() != null) {
                fileById.put(cm.getFileId(), cm);
            }

            boolean isActive = activeContact != null && activeContact.getNick().equals(key);
            if (isActive) {
                messageList.scrollTo(conv.size() - 1);
                // Входящее личное в открытом диалоге — сразу отмечаем прочитанным.
                if (!mine && !message.isBroadcast()) {
                    service.sendRead(key);
                }
            } else if (!history) {
                Contact c = Message.BROADCAST.equals(key) ? broadcastContact : contactsByNick.get(key);
                if (c != null) {
                    c.incrementUnread();
                    contactList.refresh();
                }
            }
        });
    }

    /**
     * Протокольное сообщение → UI-модель. Для «своих» личных переносит id и
     * статус доставки (атрибут {@code st}: P/D/R) — чтобы при перелогине пузыри
     * сразу показали верную галочку (ПР14).
     */
    private ChatMessage toChatMessage(Message message, boolean mine) {
        String time = formatTime(message.getTimestamp());
        boolean privateChat = mine && !message.isBroadcast();
        // ПР15: сообщение-ссылка на файл (атрибут file=1) → файловый пузырь.
        if ("1".equals(message.getAttributes().get("file"))) {
            DeliveryStatus st = privateChat ? parseStatus(message.getAttributes().get("st")) : null;
            return ChatMessage.fileMessage(message.getAttributes().get("id"), message.getFrom(),
                    time, mine, privateChat, st, message.getAttributes().get("name"),
                    parseLong(message.getAttributes().get("size")), message.getAttributes().get("mime"),
                    message.getAttributes().get("fileId"), null);
        }
        if (privateChat) {
            return new ChatMessage(message.getAttributes().get("id"), message.getFrom(),
                    message.getBody(), time, true, true, parseStatus(message.getAttributes().get("st")));
        }
        return new ChatMessage(message.getFrom(), message.getBody(), time, mine);
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Код статуса из протокола (P/D/R) → {@link DeliveryStatus}; по умолчанию «доставлено». */
    private static DeliveryStatus parseStatus(String code) {
        if ("P".equals(code)) {
            return DeliveryStatus.PENDING;
        }
        if ("R".equals(code)) {
            return DeliveryStatus.READ;
        }
        return DeliveryStatus.DELIVERED;
    }

    /** Уникальный в пределах сети id исходящего сообщения: {@code ник-N}. */
    private String nextMessageId() {
        return nick + "-" + (++messageCounter);
    }

    @Override
    public void onDelivered(String messageId) {
        Platform.runLater(() -> {
            ChatMessage cm = messageId == null ? null : outgoingById.get(messageId);
            // «Прочитано» сильнее «доставлено» — не откатываем ✓✓ обратно к ✓.
            if (cm != null && cm.getStatus() != DeliveryStatus.READ) {
                cm.setStatus(DeliveryStatus.DELIVERED);
                messageList.refresh();
            }
        });
    }

    @Override
    public void onRead(String peer) {
        Platform.runLater(() -> {
            ObservableList<ChatMessage> conv = peer == null ? null : conversations.get(peer);
            if (conv == null) {
                return;
            }
            // Собеседник открыл диалог — все наши личные пузыри к нему прочитаны.
            for (ChatMessage cm : conv) {
                if (cm.isMine() && cm.isPrivate()) {
                    cm.setStatus(DeliveryStatus.READ);
                }
            }
            messageList.refresh();
        });
    }

    @Override
    public void onFileReceived(String fileId, byte[] bytes) {
        Platform.runLater(() -> {
            ChatMessage cm = fileById.get(fileId);
            if (cm != null) {
                cm.setFileBytes(bytes);
                messageList.refresh(); // картинка перерисуется из байтов
            }
            // Если ждали сохранения (клик «Сохранить» до докачки) — теперь сохраняем.
            if (pendingSaves.remove(fileId)) {
                saveBytes(cm != null ? cm.getFileName() : "file", bytes);
            }
        });
    }

    /** Картинка-пузырь без байтов — просим сервер докачать (однократно). Зовётся из ячейки. */
    public void ensureImageLoaded(ChatMessage msg) {
        if (msg.getFileId() == null || msg.isDownloadRequested()) {
            return;
        }
        msg.setDownloadRequested(true);
        service.requestFile(msg.getFileId());
    }

    /** Сохранение файла/картинки на диск (ПР15). Зовётся из ячейки по клику. */
    public void saveFile(ChatMessage msg) {
        if (!msg.isFile()) {
            return;
        }
        if (msg.getFileBytes() != null) {
            saveBytes(msg.getFileName(), msg.getFileBytes());
        } else if (msg.getLocalFile() != null) {
            saveLocalCopy(msg);          // отправитель — копируем исходный файл
        } else if (msg.getFileId() != null) {
            pendingSaves.add(msg.getFileId()); // докачаем и сохраним по приходу байтов
            ensureImageLoaded(msg);
        }
    }

    /** Записывает байты в выбранный пользователем файл. */
    private void saveBytes(String suggestedName, byte[] bytes) {
        File dest = chooseSaveTarget(suggestedName);
        if (dest == null) {
            return;
        }
        try {
            Files.write(dest.toPath(), bytes);
            titleLabel.setText("Сохранено: " + dest.getName());
        } catch (IOException e) {
            titleLabel.setText("Не удалось сохранить: " + e.getMessage());
        }
    }

    /** Копирует локальный файл отправителя в выбранное место. */
    private void saveLocalCopy(ChatMessage msg) {
        File dest = chooseSaveTarget(msg.getFileName());
        if (dest == null) {
            return;
        }
        try {
            Files.copy(msg.getLocalFile().toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            titleLabel.setText("Сохранено: " + dest.getName());
        } catch (IOException e) {
            titleLabel.setText("Не удалось сохранить: " + e.getMessage());
        }
    }

    private File chooseSaveTarget(String suggestedName) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Сохранить как…");
        if (suggestedName != null) {
            chooser.setInitialFileName(suggestedName);
        }
        return chooser.showSaveDialog(window());
    }

    private javafx.stage.Window window() {
        return titleLabel.getScene() == null ? null : titleLabel.getScene().getWindow();
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
