package dev.ebms.adapter.in.msh;

import dev.ebms.config.EbmsSecurityProperties;
import dev.ebms.domain.Payload;
import dev.ebms.domain.exception.MessageParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
public class XmlEncryptionService {

    private static final String XENC_NS = "http://www.w3.org/2001/04/xmlenc#";
    private static final String DS_NS = "http://www.w3.org/2000/09/xmldsig#";
    private static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";
    private static final String AES256_CBC_URI = XENC_NS + "aes256-cbc";
    private static final String RSA_OAEP_URI = XENC_NS + "rsa-oaep-mgf1p";
    private static final String ENCRYPTED_KEY_TYPE_URI = XENC_NS + "EncryptedKey";
    static final String ENCRYPTED_MIME_TYPE = "application/xenc+xml";

    private final PrivateKey decryptionKey;

    @Autowired
    public XmlEncryptionService(EbmsSecurityProperties props) throws Exception {
        if (props.keystoreConfigured()) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream fis = new FileInputStream(props.getKeystorePath())) {
                ks.load(fis, props.getKeystorePassword().toCharArray());
            }
            this.decryptionKey = (PrivateKey) ks.getKey(
                    props.getKeystoreAlias(), props.getKeystorePassword().toCharArray());
        } else {
            this.decryptionKey = null;
        }
    }

    XmlEncryptionService(PrivateKey decryptionKey) {
        this.decryptionKey = decryptionKey;
    }

    public static XmlEncryptionService disabled() {
        return new XmlEncryptionService((PrivateKey) null);
    }

    public boolean isEnabled() {
        return decryptionKey != null;
    }

    /**
     * Encrypts each payload with AES-256-CBC, wraps the symmetric key with the
     * recipient's public key (RSA-OAEP), adds xenc:EncryptedKey to the SOAP header,
     * and returns payloads whose content is replaced by xenc:EncryptedData XML.
     * The SOAP document is modified in place (EncryptedKey added to header).
     */
    public List<Payload> encryptPayloads(Document doc, List<Payload> payloads,
                                          X509Certificate recipientCert) {
        try {
            return doEncryptPayloads(doc, payloads, recipientCert);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt payloads", e);
        }
    }

    /**
     * Finds xenc:EncryptedKey in the SOAP header, decrypts the symmetric key with
     * our private key, and decrypts any payload whose MIME type is application/xenc+xml.
     * Returns payloads unchanged if no EncryptedKey is present.
     */
    public List<Payload> decryptPayloads(Document doc, List<Payload> payloads) {
        try {
            return doDecryptPayloads(doc, payloads);
        } catch (MessageParseException e) {
            throw e;
        } catch (Exception e) {
            throw new MessageParseException("Failed to decrypt payloads", "SecurityFailure", e);
        }
    }

    private List<Payload> doEncryptPayloads(Document doc, List<Payload> payloads,
                                             X509Certificate recipientCert) throws Exception {
        if (payloads.isEmpty()) return payloads;

        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        SecretKey symmetricKey = kg.generateKey();

        Cipher keyCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        keyCipher.init(Cipher.WRAP_MODE, recipientCert.getPublicKey());
        byte[] wrappedKey = keyCipher.wrap(symmetricKey);

        String encKeyId = "EK-" + UUID.randomUUID();
        addEncryptedKeyToHeader(doc, wrappedKey, recipientCert, encKeyId);

        List<Payload> result = new ArrayList<>();
        for (Payload payload : payloads) {
            result.add(encryptPayload(payload, symmetricKey, encKeyId));
        }
        return result;
    }

    private List<Payload> doDecryptPayloads(Document doc, List<Payload> payloads) throws Exception {
        if (payloads.isEmpty() || decryptionKey == null) return payloads;

        NodeList keyNodes = doc.getElementsByTagNameNS(XENC_NS, "EncryptedKey");
        if (keyNodes.getLength() == 0) return payloads;

        byte[] wrappedKey = extractCipherValue((Element) keyNodes.item(0));

        Cipher keyCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        keyCipher.init(Cipher.UNWRAP_MODE, decryptionKey);
        SecretKey symmetricKey = (SecretKey) keyCipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY);

        List<Payload> result = new ArrayList<>();
        for (Payload payload : payloads) {
            result.add(decryptPayload(payload, symmetricKey));
        }
        return result;
    }

    private Payload encryptPayload(Payload payload, SecretKey key, String encKeyId) throws Exception {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
        byte[] ciphertext = cipher.doFinal(payload.content());

        // XML Encryption 1.0: IV prepended to ciphertext in CipherValue
        byte[] ivAndCiphertext = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, ivAndCiphertext, 0, iv.length);
        System.arraycopy(ciphertext, 0, ivAndCiphertext, iv.length, ciphertext.length);

        String encDataXml = buildEncryptedDataXml(
                Base64.getEncoder().encodeToString(ivAndCiphertext), encKeyId, payload.mimeType());
        return new Payload(payload.contentId(), ENCRYPTED_MIME_TYPE,
                encDataXml.getBytes(StandardCharsets.UTF_8));
    }

    private Payload decryptPayload(Payload payload, SecretKey key) throws Exception {
        if (!ENCRYPTED_MIME_TYPE.equals(payload.mimeType())) return payload;

        Document encDoc = parseXml(payload.content());
        NodeList dataNodes = encDoc.getElementsByTagNameNS(XENC_NS, "EncryptedData");
        if (dataNodes.getLength() == 0) return payload;

        Element encDataEl = (Element) dataNodes.item(0);
        String originalMimeType = encDataEl.getAttribute("MimeType");
        if (originalMimeType == null || originalMimeType.isBlank()) {
            originalMimeType = "application/octet-stream";
        }

        byte[] ivAndCiphertext = extractCipherValue(encDataEl);
        byte[] iv = new byte[16];
        byte[] ciphertext = new byte[ivAndCiphertext.length - 16];
        System.arraycopy(ivAndCiphertext, 0, iv, 0, 16);
        System.arraycopy(ivAndCiphertext, 16, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
        return new Payload(payload.contentId(), originalMimeType, cipher.doFinal(ciphertext));
    }

    private void addEncryptedKeyToHeader(Document doc, byte[] wrappedKey,
                                          X509Certificate cert, String id) throws Exception {
        Element header = (Element) doc.getElementsByTagNameNS(SoapMimeParser.SOAP_NS, "Header").item(0);

        Element encKey = doc.createElementNS(XENC_NS, "xenc:EncryptedKey");
        encKey.setAttributeNS(XMLNS_NS, "xmlns:xenc", XENC_NS);
        encKey.setAttributeNS(XMLNS_NS, "xmlns:ds", DS_NS);
        encKey.setAttribute("Id", id);

        Element encMethod = doc.createElementNS(XENC_NS, "xenc:EncryptionMethod");
        encMethod.setAttribute("Algorithm", RSA_OAEP_URI);
        encKey.appendChild(encMethod);

        Element keyInfo = doc.createElementNS(DS_NS, "ds:KeyInfo");
        Element x509Data = doc.createElementNS(DS_NS, "ds:X509Data");
        Element x509Cert = doc.createElementNS(DS_NS, "ds:X509Certificate");
        x509Cert.setTextContent(Base64.getEncoder().encodeToString(cert.getEncoded()));
        x509Data.appendChild(x509Cert);
        keyInfo.appendChild(x509Data);
        encKey.appendChild(keyInfo);

        Element cipherData = doc.createElementNS(XENC_NS, "xenc:CipherData");
        Element cipherValue = doc.createElementNS(XENC_NS, "xenc:CipherValue");
        cipherValue.setTextContent(Base64.getEncoder().encodeToString(wrappedKey));
        cipherData.appendChild(cipherValue);
        encKey.appendChild(cipherData);

        header.appendChild(encKey);
    }

    private String buildEncryptedDataXml(String base64CipherValue, String encKeyId,
                                          String originalMimeType) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<xenc:EncryptedData");
        sb.append(" xmlns:xenc=\"").append(XENC_NS).append("\"");
        sb.append(" xmlns:ds=\"").append(DS_NS).append("\"");
        if (originalMimeType != null && !originalMimeType.isBlank()) {
            sb.append(" MimeType=\"").append(originalMimeType).append("\"");
        }
        sb.append(" Type=\"").append(XENC_NS).append("Content\">");
        sb.append("<xenc:EncryptionMethod Algorithm=\"").append(AES256_CBC_URI).append("\"/>");
        sb.append("<ds:KeyInfo>");
        sb.append("<xenc:RetrievalMethod URI=\"#").append(encKeyId).append("\"");
        sb.append(" Type=\"").append(ENCRYPTED_KEY_TYPE_URI).append("\"/>");
        sb.append("</ds:KeyInfo>");
        sb.append("<xenc:CipherData><xenc:CipherValue>")
          .append(base64CipherValue)
          .append("</xenc:CipherValue></xenc:CipherData>");
        sb.append("</xenc:EncryptedData>");
        return sb.toString();
    }

    private byte[] extractCipherValue(Element parent) {
        NodeList cipherValues = parent.getElementsByTagNameNS(XENC_NS, "CipherValue");
        if (cipherValues.getLength() == 0) {
            throw new MessageParseException("No xenc:CipherValue found", "SecurityFailure");
        }
        return Base64.getDecoder().decode(cipherValues.item(0).getTextContent().trim());
    }

    private Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
}
