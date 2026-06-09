package org.example.client.controller;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
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
import java.util.ArrayList;
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
    /**
     * id всех уже показанных сообщений (ПР17). Сервер при каждом входе заново
     * проигрывает историю, поэтому после авто-переподключения те же сообщения
     * приходят повторно — по id отбрасываем дубли, чтобы лента не задваивалась.
     */
    private final Set<String> seenMessageIds = new HashSet<>();
    /** Счётчик для клиентских id исходящих сообщений (в паре с меткой сессии). */
    private int messageCounter;
    /**
     * Метка текущей сессии клиента. Фиксируется один раз при входе и входит в
     * каждый клиентский id ({@code ник-метка-N}). Без неё счётчик обнулялся бы
     * при каждом перезапуске, id вроде {@code Alice-1} повторялись бы между
     * сессиями и совпадали с уже накопленной историей — тогда дедуп
     * {@link #seenMessageIds} (ПР17) принимал бы новое живое сообщение за
     * «дубль истории» и не показывал его. Метка делает id уникальными между
     * запусками, оставаясь стабильной внутри сессии (в т.ч. при реконнекте).
     */
    private String sessionTag;
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

    // --- групповые комнаты (ПР16) ---
    /** Псевдо-контакты комнат, в которых я состою. Ключ — «#имя». */
    private final Map<String, Contact> roomContacts = new HashMap<>();
    /** Состав комнат для заголовка: «#имя» → список участников. */
    private final Map<String, List<String>> roomMembers = new HashMap<>();
    /** Последний полученный список существующих комнат (для диалога «войти»). */
    private final List<String> availableRooms = new ArrayList<>();
    /** Комната, которую надо авто-выбрать, как только придёт её состав (после «войти»). */
    private String pendingRoomSelect;

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
        // Метка сессии для уникальных id между перезапусками (см. поле sessionTag).
        this.sessionTag = Long.toString(System.currentTimeMillis(), 36);
        this.service.setListener(this);

        titleLabel.setText("Вы вошли как: " + nick);

        messageList.setCellFactory(lv -> new ChatBubbleCell(this));

        contactList.setItems(contacts);
        contactList.setCellFactory(lv -> new ContactCell(this));
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

    /**
     * ПР16: закрепляет комнату {@code roomKey} («#имя») в списке слева, если её
     * там ещё нет. Комната ставится сразу под «Общим чатом».
     */
    private void ensureRoomContact(String roomKey) {
        if (roomKey == null || roomContacts.containsKey(roomKey)) {
            return;
        }
        Contact c = Contact.room(roomKey);
        roomContacts.put(roomKey, c);
        contacts.add(contacts.indexOf(broadcastContact) + 1, c);
    }

    /** Контакт по ключу диалога (общий чат / комната «#имя» / ник собеседника). */
    private Contact contactFor(String key) {
        if (Message.BROADCAST.equals(key)) {
            return broadcastContact;
        }
        if (key.startsWith(Message.ROOM_PREFIX)) {
            return roomContacts.get(key);
        }
        return contactsByNick.get(key);
    }

    /** Открыт ли сейчас общий чат (адресат — broadcast). */
    private boolean isBroadcastTarget() {
        return activeContact == null || activeContact.isBroadcast();
    }

    /** Открыта ли сейчас личка с реальным пользователем (не общий чат и не комната). */
    private boolean isPersonalTarget() {
        return activeContact != null && !activeContact.isBroadcast() && !activeContact.isRoom();
    }

    /** Адрес для отправки из текущего диалога: «*», «#имя» или ник. */
    private String currentTarget() {
        return isBroadcastTarget() ? Message.BROADCAST : activeContact.getNick();
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
        // У комнат и общего чата квитанций нет.
        if (!sel.isBroadcast() && !sel.isRoom()) {
            service.sendRead(sel.getNick());
        }
        updateTitle();
    }

    /** Заголовок окна отражает, с кем/где сейчас разговор. */
    private void updateTitle() {
        String where;
        if (activeContact == null || activeContact.isBroadcast()) {
            where = "Общий чат";
        } else if (activeContact.isRoom()) {
            List<String> members = roomMembers.get(activeContact.getNick());
            where = "Комната " + activeContact.getNick()
                    + (members == null || members.isEmpty()
                        ? "" : " — участники: " + String.join(", ", members));
        } else {
            where = "Личный чат с " + activeContact.getNick();
        }
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
        if (!service.isConnected()) {
            titleLabel.setText("Нет связи с сервером — сообщение не отправлено");
            return;
        }
        boolean personal = isPersonalTarget();
        String target = currentTarget();
        // ПР17: id выдаём всегда (и бродкасту/комнате тоже) и запоминаем — тогда
        // при повторном проигрывании истории после реконнекта свои же сообщения
        // распознаются как дубли и не задваиваются.
        String id = nextMessageId();
        seenMessageIds.add(id);
        // Своё сообщение сразу в ленту текущего диалога (справа). Личное стартует
        // со статусом «ждёт доставки» (⏳) — квитанции обновят галочку (ПР14).
        // Общий чат и комнаты — без галочек (как и раньше бродкаст).
        ChatMessage own = personal
                ? new ChatMessage(id, nick, text, LocalTime.now().format(TIME_FMT), true,
                        true, DeliveryStatus.PENDING)
                : new ChatMessage(nick, text, LocalTime.now().format(TIME_FMT), true);
        if (personal) {
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
        if (!service.isConnected()) {
            titleLabel.setText("Нет связи с сервером — файл не отправлен");
            return;
        }
        boolean personal = isPersonalTarget();
        String target = currentTarget();
        // ref = id сообщения-ссылки на сервере (см. handleFileEnd) — помним для
        // дедупа при повторном проигрывании истории после реконнекта (ПР17).
        String ref = nextMessageId();
        seenMessageIds.add(ref);
        // Локальный пузырь сразу: у отправителя превью берётся из самого файла,
        // личный — со статусом «ждёт доставки» (галочки из ПР14). Комната/общий
        // чат — без галочек.
        ChatMessage bubble = ChatMessage.fileMessage(personal ? ref : null, nick,
                LocalTime.now().format(TIME_FMT), true, personal,
                personal ? DeliveryStatus.PENDING : null, file.getName(), file.length(),
                null, null, file);
        if (personal) {
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

    /**
     * ПР16: диалог «войти/создать комнату». Имя без префикса дополняется «#».
     * Если комната существует — сервер просто добавит нас в участники, иначе
     * создаст её. По приходу состава комнату авто-выбираем (см. {@code pendingRoomSelect}).
     */
    @FXML
    private void onJoinRoom() {
        if (service == null) {
            return;
        }
        service.requestRoomList(); // освежим список к следующему открытию диалога
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Комнаты");
        dialog.setHeaderText(availableRooms.isEmpty()
                ? "Введите имя комнаты, чтобы создать её:"
                : "Доступные комнаты: " + String.join(", ", availableRooms)
                        + "\nВведите имя, чтобы войти или создать новую:");
        dialog.setContentText("Имя комнаты:");
        if (window() != null) {
            dialog.initOwner(window());
        }
        dialog.showAndWait().ifPresent(name -> {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            String roomKey = trimmed.startsWith(Message.ROOM_PREFIX)
                    ? trimmed : Message.ROOM_PREFIX + trimmed;
            pendingRoomSelect = roomKey;
            service.joinRoom(roomKey);
        });
    }

    /** ПР16: покинуть комнату — из контекст-меню её строки. Чистит ленту и список. */
    public void leaveRoom(String roomKey) {
        if (service == null || roomKey == null) {
            return;
        }
        service.leaveRoom(roomKey);
        roomContacts.remove(roomKey);
        roomMembers.remove(roomKey);
        conversations.remove(roomKey);
        contacts.removeIf(c -> c.isRoom() && c.getNick().equals(roomKey));
        if (activeContact != null && activeContact.isRoom()
                && activeContact.getNick().equals(roomKey)) {
            contactList.getSelectionModel().select(broadcastContact);
        }
    }

    // ---- ClientServiceListener: события «снизу» ----

    @Override
    public void onConnected() {
        Platform.runLater(this::updateTitle);
    }

    @Override
    public void onMessage(Message message) {
        Platform.runLater(() -> {
            // ПР17: дубль из повторного проигрывания истории после реконнекта — пропускаем.
            String msgId = message.getAttributes().get("id");
            if (msgId != null && !seenMessageIds.add(msgId)) {
                // Но галочку своего личного пузыря освежим: статус мог измениться,
                // пока мы были офлайн (доставлено/прочитано). READ обратно не катим.
                ChatMessage existing = outgoingById.get(msgId);
                String st = message.getAttributes().get("st");
                if (existing != null && st != null && existing.getStatus() != DeliveryStatus.READ) {
                    existing.setStatus(parseStatus(st));
                    messageList.refresh();
                }
                return;
            }
            boolean mine = nick.equals(message.getFrom());
            // Broadcast → общий чат; комната → её лента «#имя»; личка → диалог с
            // собеседником (для своих же сообщений из истории собеседник — адресат).
            String key;
            if (message.isBroadcast()) {
                key = Message.BROADCAST;
            } else if (message.isRoom()) {
                key = message.getTo();
            } else {
                key = mine ? message.getTo() : message.getFrom();
            }
            // Метка истории (ПР9): такие сообщения проигрываются при входе и не
            // должны поднимать счётчик «непрочитано».
            boolean history = "1".equals(message.getAttributes().get("hist"));

            // Закрепляем диалог в списке слева: комнату (ПР16) или личного
            // собеседника (ПР10) — в т.ч. при проигрывании истории.
            if (message.isRoom()) {
                ensureRoomContact(key);
            } else if (!message.isBroadcast()) {
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
                // Входящее личное в открытом диалоге — сразу отмечаем прочитанным
                // (комнат не касается: там квитанций нет).
                if (!mine && !message.isBroadcast() && !message.isRoom()) {
                    service.sendRead(key);
                }
            } else if (!history) {
                Contact c = contactFor(key);
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
        boolean privateChat = mine && !message.isBroadcast() && !message.isRoom();
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

    /** Уникальный в пределах сети id исходящего сообщения: {@code ник-метка-N}. */
    private String nextMessageId() {
        return nick + "-" + sessionTag + "-" + (++messageCounter);
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
            contacts.addAll(roomContacts.values()); // ПР16: мои комнаты — следом
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
                        || key.startsWith(Message.ROOM_PREFIX) // комнаты добавлены выше
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
            if (activeContact != null && activeContact.isRoom()) {
                Contact still = roomContacts.get(activeContact.getNick());
                if (still != null) {
                    toSelect = still; // комната переживает перестроение списка (ПР16)
                }
            } else if (activeContact != null && !activeContact.isBroadcast()) {
                Contact still = contactsByNick.get(activeContact.getNick());
                if (still != null) {
                    toSelect = still;
                }
            }
            contactList.getSelectionModel().select(toSelect);
        });
    }

    @Override
    public void onRoomList(List<String> rooms) {
        Platform.runLater(() -> {
            availableRooms.clear();
            availableRooms.addAll(rooms);
        });
    }

    @Override
    public void onRoomMembers(String room, List<String> members) {
        Platform.runLater(() -> {
            if (room == null) {
                return;
            }
            // Получили состав комнаты ⇒ я её участник: закрепляем строку слева.
            roomMembers.put(room, members);
            ensureRoomContact(room);
            contactList.refresh();
            if (activeContact != null && activeContact.isRoom()
                    && activeContact.getNick().equals(room)) {
                updateTitle(); // обновим участников в заголовке открытой комнаты
            }
            // Только что вошли через диалог — авто-выбираем эту комнату.
            if (room.equals(pendingRoomSelect)) {
                pendingRoomSelect = null;
                Contact c = roomContacts.get(room);
                if (c != null) {
                    contactList.getSelectionModel().select(c);
                }
            }
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
    public void onReconnecting() {
        Platform.runLater(() -> titleLabel.setText("⟳ Связь потеряна — переподключение…"));
    }

    @Override
    public void onReconnected() {
        // Связь восстановлена: сервер заново пришлёт списки/историю (дубли отсеет
        // seenMessageIds), а заголовок возвращаем к обычному виду текущего диалога.
        Platform.runLater(this::updateTitle);
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
        /** Контекст-меню комнаты (ПР16): «Покинуть». Целевая комната — текущий item ячейки. */
        private final ContextMenu roomMenu;

        ContactCell(MainChatController controller) {
            MenuItem leave = new MenuItem("Покинуть комнату");
            leave.setOnAction(e -> {
                Contact item = getItem();
                if (item != null && item.isRoom()) {
                    controller.leaveRoom(item.getNick());
                }
            });
            this.roomMenu = new ContextMenu(leave);
        }

        @Override
        protected void updateItem(Contact item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("status-online", "status-away", "status-offline",
                    "contact-broadcast", "contact-room");
            if (empty || item == null) {
                setText(null);
                setContextMenu(null);
                return;
            }
            String label;
            if (item.isBroadcast()) {
                label = "# Общий чат";
            } else if (item.isRoom()) {
                // nick = «#имя» → показываем «# имя».
                label = "# " + item.getNick().substring(Message.ROOM_PREFIX.length());
            } else {
                label = item.getNick();
            }
            if (item.getUnread() > 0) {
                label += "  (" + item.getUnread() + ")";
            }
            setText(label);
            setContextMenu(item.isRoom() ? roomMenu : null);
            if (item.isBroadcast()) {
                getStyleClass().add("contact-broadcast");
            } else if (item.isRoom()) {
                getStyleClass().add("contact-room");
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
