package org.example.net;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Enumeration;

/**
 * Шифрование канала (ПР18): единая точка создания TLS-сокетов для сервера и
 * клиента. До этого ПР весь обмен — включая пароль при входе — шёл открытым
 * текстом; теперь поверх TCP поднимается TLS, и трафик нельзя ни прочитать, ни
 * подменить «человеком посередине».
 *
 * <p>Зеркало {@link org.example.protocol.ProtocolFactory}: вся настройка TLS
 * собрана здесь, а {@code ChatServer}/{@code ClientService} лишь меняют
 * {@code new ServerSocket(...)} → {@link #newServerSocket(int)} и
 * {@code new Socket(...)} → {@link #newSocket(String, int)}. Класс
 * {@link Connection} и протокол не затронуты.
 *
 * <p><b>Доверие.</b> В ресурсах лежит самоподписанный {@code server.p12} —
 * это удостоверение сервера (приватный ключ + сертификат). Клиент достаёт из
 * того же файла <em>только сертификат</em> и доверяет исключительно ему
 * («пиннинг»): подменить сервер без его приватного ключа невозможно. Поэтому
 * проверку имени хоста не включаем — достаточно одного конкретного сертификата,
 * и клиент свободно подключается к localhost или к адресу в локальной сети.
 *
 * <p><b>Это dev-удостоверение.</b> Keystore лежит в репозитории, пароль к нему
 * публичный и ничего не защищает (файл и так открыт) — он лишь нужен формату.
 * Для боевого развёртывания keystore генерируют заново и держат вне репозитория.
 */
public final class Tls {

    /** Самоподписанный keystore сервера на classpath (приватный ключ + сертификат). */
    private static final String KEYSTORE_RESOURCE = "/org/example/net/server.p12";
    /** Пароль dev-keystore: публичный, защищает только формат PKCS12. */
    private static final char[] STORE_PASSWORD = "changeit".toCharArray();
    /** Версия протокола: самый свежий TLS, поддержанный JDK 26. */
    private static final String PROTOCOL = "TLSv1.3";

    private Tls() { }

    /**
     * Серверный слушающий сокет с TLS: предъявляет подключающимся сертификат из
     * {@code server.p12}. Рукопожатие проходит лениво при первом обмене.
     */
    public static ServerSocket newServerSocket(int port) throws IOException {
        SSLContext ctx = serverContext();
        SSLServerSocketFactory factory = ctx.getServerSocketFactory();
        SSLServerSocket socket = (SSLServerSocket) factory.createServerSocket(port);
        return socket;
    }

    /**
     * Клиентский TLS-сокет к серверу. Доверяет только закреплённому сертификату
     * сервера; рукопожатие выполняется сразу, чтобы недоверенный сервер отвалился
     * на подключении, а не на первом сообщении.
     */
    public static Socket newSocket(String host, int port) throws IOException {
        SSLContext ctx = clientContext();
        SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket(host, port);
        socket.startHandshake(); // быстрый отказ при неверном/подменённом сертификате
        return socket;
    }

    /** Контекст сервера: ключ-менеджер на основе приватного ключа из keystore. */
    private static SSLContext serverContext() throws IOException {
        try {
            KeyStore keyStore = loadKeystore();
            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, STORE_PASSWORD);
            SSLContext ctx = SSLContext.getInstance(PROTOCOL);
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IOException("Не удалось настроить TLS сервера", e);
        }
    }

    /** Контекст клиента: доверяет только сертификату(ам) из bundled keystore. */
    private static SSLContext clientContext() throws IOException {
        try {
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(pinnedTrustStore());
            SSLContext ctx = SSLContext.getInstance(PROTOCOL);
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IOException("Не удалось настроить TLS клиента", e);
        }
    }

    /**
     * Строит truststore из одних сертификатов keystore. {@code getCertificate}
     * по ключевому алиасу отдаёт открытый сертификат (без приватного ключа), так
     * что клиент закрепляет именно его и больше ничего.
     */
    private static KeyStore pinnedTrustStore() throws GeneralSecurityException, IOException {
        KeyStore source = loadKeystore();
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        for (Enumeration<String> aliases = source.aliases(); aliases.hasMoreElements(); ) {
            String alias = aliases.nextElement();
            Certificate cert = source.getCertificate(alias);
            if (cert != null) {
                trust.setCertificateEntry(alias, cert);
            }
        }
        return trust;
    }

    /** Загружает bundled keystore с classpath. */
    private static KeyStore loadKeystore() throws GeneralSecurityException, IOException {
        try (InputStream in = Tls.class.getResourceAsStream(KEYSTORE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Не найден keystore в ресурсах: " + KEYSTORE_RESOURCE);
            }
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, STORE_PASSWORD);
            return keyStore;
        }
    }
}
