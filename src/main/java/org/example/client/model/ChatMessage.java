package org.example.client.model;

import java.io.File;
import java.util.Locale;

/**
 * UI-модель одного сообщения в ленте чата.
 *
 * <p>Отделена от протокольного {@code org.example.protocol.Message}: здесь
 * хранится только то, что нужно для отрисовки пузыря.
 *
 * <p>ПР14: у «своих» личных сообщений есть {@link #getId() id} и изменяемый
 * {@link #getStatus() статус} ✓/✓✓.
 *
 * <p>ПР15: сообщение может быть <b>файлом</b> ({@link #isFile()}). Тогда у него
 * есть имя/размер/mime и либо {@link #getLocalFile() локальный файл} (у
 * отправителя — для превью и сохранения без скачивания), либо
 * {@link #getFileId() серверный fileId} (у получателя — для докачки). Скачанные
 * байты кэшируются в {@link #getFileBytes()} (мутабельно, обновляется через
 * {@code ListView.refresh()}).
 */
public final class ChatMessage {

    private final String id;
    private final String sender;
    private final String text;
    private final String time;
    private final boolean mine;
    private final boolean privateChat;
    private DeliveryStatus status;

    // ---- файл (ПР15) ----
    private final boolean file;
    private final String fileName;
    private final long fileSize;
    private final String mime;
    private final String fileId;
    private final File localFile;
    private byte[] fileBytes;
    private boolean downloadRequested;

    private ChatMessage(String id, String sender, String text, String time, boolean mine,
                        boolean privateChat, DeliveryStatus status, boolean file, String fileName,
                        long fileSize, String mime, String fileId, File localFile) {
        this.id = id;
        this.sender = sender;
        this.text = text;
        this.time = time;
        this.mine = mine;
        this.privateChat = privateChat;
        this.status = status;
        this.file = file;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.mime = mime;
        this.fileId = fileId;
        this.localFile = localFile;
    }

    /** Полный конструктор текстового сообщения (ПР14). */
    public ChatMessage(String id, String sender, String text, String time, boolean mine,
                       boolean privateChat, DeliveryStatus status) {
        this(id, sender, text, time, mine, privateChat, status, false, null, 0, null, null, null);
    }

    /** Упрощённый конструктор для сообщений без статуса (чужие, общий чат). */
    public ChatMessage(String sender, String text, String time, boolean mine) {
        this(null, sender, text, time, mine, false, null, false, null, 0, null, null, null);
    }

    /** Файл-сообщение (ПР15): {@code localFile} у отправителя, иначе {@code fileId} у получателя. */
    public static ChatMessage fileMessage(String id, String sender, String time, boolean mine,
                                          boolean privateChat, DeliveryStatus status, String fileName,
                                          long fileSize, String mime, String fileId, File localFile) {
        return new ChatMessage(id, sender, null, time, mine, privateChat, status,
                true, fileName, fileSize, mime, fileId, localFile);
    }

    public String getId()             { return id; }
    public String getSender()         { return sender; }
    public String getText()           { return text; }
    public String getTime()           { return time; }
    public boolean isMine()           { return mine; }
    public boolean isPrivate()        { return privateChat; }
    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }

    public boolean isFile()           { return file; }
    public String getFileName()       { return fileName; }
    public long getFileSize()         { return fileSize; }
    public String getMime()           { return mime; }
    public String getFileId()         { return fileId; }
    public File getLocalFile()        { return localFile; }
    public byte[] getFileBytes()      { return fileBytes; }
    public void setFileBytes(byte[] b) { this.fileBytes = b; }
    public boolean isDownloadRequested() { return downloadRequested; }
    public void setDownloadRequested(boolean v) { this.downloadRequested = v; }

    /** Картинка ли это (по mime или расширению) — такие показываем превью-миниатюрой. */
    public boolean isImage() {
        if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return true;
        }
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".bmp");
    }
}
