package org.example.protocol;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

/**
 * XML-кодек протокола (JAXP/DOM) — формат «на проводе» для ПР4.
 *
 * <p>Заменяет {@link TextProtocolCodec}, не затрагивая ни сетевой слой, ни GUI:
 * подмена идёт через {@link ProtocolFactory} (паттерн «Стратегия»). Контракт
 * {@link Connection «одна строка = одно сообщение»} сохранён — документ
 * сериализуется БЕЗ переносов строк и без XML-декларации.
 *
 * <p>Формат одного сообщения:
 * <pre>{@code
 * <msg type="MESSAGE" from="alice" to="*" ts="1700000000000" body="привет">
 *   <attr n="ключ" v="значение"/>
 * </msg>
 * }</pre>
 * Все поля лежат в <b>атрибутах</b>, а не в текстовом содержимом: трансформер
 * экранирует переводы строк в значениях атрибутов как {@code &#10;}/{@code &#13;},
 * поэтому многострочное тело не разорвёт построчный кадр протокола. Необязательные
 * поля ({@code from}/{@code to}/{@code body}) при {@code null} опускаются.
 * Расширяемая мапа {@link Message#getAttributes()} едет в дочерних {@code <attr>}.
 */
public class XmlProtocolCodec implements ProtocolCodec {

    private static final String ROOT = "msg";
    private static final String ATTR_EL = "attr";

    // Фабрики потокобезопасны на чтение конфигурации; сами builder/transformer —
    // нет, поэтому их создаём на каждый вызов (encode и decode могут идти из
    // разных потоков одного соединения: отправка из роутера, чтение из читателя).
    private static final DocumentBuilderFactory DBF = newSecureFactory();
    private static final TransformerFactory TF = TransformerFactory.newInstance();

    @Override
    public String encode(Message m) throws ProtocolException {
        try {
            Document doc = DBF.newDocumentBuilder().newDocument();
            Element root = doc.createElement(ROOT);
            root.setAttribute("type", m.getType().name());
            setIfPresent(root, "from", m.getFrom());
            setIfPresent(root, "to", m.getTo());
            root.setAttribute("ts", Long.toString(m.getTimestamp()));
            setIfPresent(root, "body", m.getBody());
            for (Map.Entry<String, String> e : m.getAttributes().entrySet()) {
                Element a = doc.createElement(ATTR_EL);
                a.setAttribute("n", e.getKey());
                a.setAttribute("v", e.getValue() == null ? "" : e.getValue());
                root.appendChild(a);
            }
            doc.appendChild(root);
            return serialize(doc);
        } catch (ParserConfigurationException | TransformerException e) {
            throw new ProtocolException("Не удалось закодировать сообщение в XML", e);
        }
    }

    @Override
    public Message decode(String raw) throws ProtocolException {
        if (raw == null || raw.isBlank()) {
            throw new ProtocolException("Пустая строка протокола");
        }
        try {
            DocumentBuilder builder = DBF.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(raw)));
            Element root = doc.getDocumentElement();
            if (root == null || !ROOT.equals(root.getNodeName())) {
                throw new ProtocolException("Ожидался корневой <" + ROOT + ">: " + raw);
            }

            MessageType type = parseType(root.getAttribute("type"));
            String from = emptyToNull(root.getAttribute("from"));
            String to = emptyToNull(root.getAttribute("to"));
            String body = emptyToNull(root.getAttribute("body"));
            long ts = parseTs(root.getAttribute("ts"));

            Message msg = new Message(type, from, to, body, ts);
            readAttributes(root, msg);
            return msg;
        } catch (ProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new ProtocolException("Ошибка разбора XML: " + raw, e);
        }
    }

    // ---- helpers ----

    private static void setIfPresent(Element el, String name, String value) {
        if (value != null) {
            el.setAttribute(name, value);
        }
    }

    private static MessageType parseType(String value) throws ProtocolException {
        if (value == null || value.isEmpty()) {
            throw new ProtocolException("Отсутствует атрибут type");
        }
        try {
            return MessageType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Неизвестный тип сообщения: " + value, e);
        }
    }

    private static long parseTs(String value) throws ProtocolException {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ProtocolException("Некорректная метка времени: " + value, e);
        }
    }

    /** Считывает дочерние {@code <attr n=.. v=..>} в расширяемую мапу сообщения. */
    private static void readAttributes(Element root, Message msg) {
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && ATTR_EL.equals(node.getNodeName())) {
                Element a = (Element) node;
                String name = a.getAttribute("n");
                if (!name.isEmpty()) {
                    msg.getAttributes().put(name, a.getAttribute("v"));
                }
            }
        }
    }

    /** Сериализует документ в ОДНУ строку: без декларации и без отступов/переносов. */
    private static String serialize(Document doc) throws TransformerException {
        Transformer t = TF.newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        t.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    private static DocumentBuilderFactory newSecureFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // Защита от XXE: запрещаем DOCTYPE и внешние сущности.
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (ParserConfigurationException ignored) {
            // Если парсер не знает фичу — продолжаем с тем, что есть.
        }
        f.setExpandEntityReferences(false);
        return f;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
