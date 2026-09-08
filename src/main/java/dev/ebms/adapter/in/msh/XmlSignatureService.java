package dev.ebms.adapter.in.msh;

import dev.ebms.config.EbmsSecurityProperties;
import dev.ebms.domain.exception.MessageParseException;
import org.apache.xml.security.Init;
import org.apache.xml.security.algorithms.MessageDigestAlgorithm;
import org.apache.xml.security.c14n.Canonicalizer;
import org.apache.xml.security.keys.KeyInfo;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.transforms.Transforms;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

@Service
public class XmlSignatureService {

    private static final String DS_NS = "http://www.w3.org/2000/09/xmldsig#";

    private final PrivateKey signingKey;
    private final X509Certificate signingCert;

    @org.springframework.beans.factory.annotation.Autowired
    public XmlSignatureService(EbmsSecurityProperties props) throws GeneralSecurityException, IOException {
        Init.init();
        if (props.keystoreConfigured()) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream fis = new FileInputStream(props.getKeystorePath())) {
                ks.load(fis, props.getKeystorePassword().toCharArray());
            }
            String alias = props.getKeystoreAlias();
            signingKey = (PrivateKey) ks.getKey(alias, props.getKeystorePassword().toCharArray());
            signingCert = (X509Certificate) ks.getCertificate(alias);
        } else {
            signingKey = null;
            signingCert = null;
        }
    }

    XmlSignatureService(PrivateKey key, X509Certificate cert) {
        Init.init();
        this.signingKey = key;
        this.signingCert = cert;
    }

    public static XmlSignatureService disabled() {
        return new XmlSignatureService(null, null);
    }

    public boolean isEnabled() {
        return signingKey != null;
    }

    public void sign(Document doc) {
        try {
            Element soapHeader = requireElement(doc, SoapMimeParser.SOAP_NS, "Header");
            Element msgHeader = requireElement(doc, SoapMimeParser.EB_NS, "MessageHeader");
            Element soapBody = requireElement(doc, SoapMimeParser.SOAP_NS, "Body");

            assignId(msgHeader, "_messageHeader");
            assignId(soapBody, "_body");

            NodeList ackNodes = doc.getElementsByTagNameNS(SoapMimeParser.EB_NS, "AckRequested");
            Element ackRequested = ackNodes.getLength() > 0 ? (Element) ackNodes.item(0) : null;
            if (ackRequested != null) {
                assignId(ackRequested, "_ackRequested");
            }

            XMLSignature sig = new XMLSignature(doc, "",
                    XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256,
                    Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);

            soapHeader.appendChild(sig.getElement());

            sig.addDocument("#_messageHeader", newTransforms(doc), MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA256);
            sig.addDocument("#_body", newTransforms(doc), MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA256);
            if (ackRequested != null) {
                sig.addDocument("#_ackRequested", newTransforms(doc), MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA256);
            }

            sig.addKeyInfo(signingCert);
            sig.sign(signingKey);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign SOAP document", e);
        }
    }

    public void verify(Document doc) {
        try {
            NodeList sigNodes = doc.getElementsByTagNameNS(DS_NS, "Signature");
            if (sigNodes.getLength() == 0) {
                return;
            }

            Element sigElement = (Element) sigNodes.item(0);
            registerIdAttributes(doc.getDocumentElement());

            XMLSignature sig = new XMLSignature(sigElement, "");
            KeyInfo keyInfo = sig.getKeyInfo();
            X509Certificate cert = keyInfo != null ? keyInfo.getX509Certificate() : null;

            if (cert == null) {
                throw new MessageParseException("Signature present but no certificate in KeyInfo", "SecurityFailure");
            }
            if (!sig.checkSignatureValue(cert)) {
                throw new MessageParseException("Signature verification failed", "SecurityFailure");
            }
        } catch (MessageParseException e) {
            throw e;
        } catch (Exception e) {
            throw new MessageParseException("Signature verification error: " + e.getMessage(), "SecurityFailure", e);
        }
    }

    private void assignId(Element element, String id) {
        element.setAttribute("Id", id);
        element.setIdAttribute("Id", true);
    }

    private void registerIdAttributes(Element element) {
        if (element.hasAttribute("Id")) {
            element.setIdAttribute("Id", true);
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                registerIdAttributes((Element) child);
            }
        }
    }

    private Transforms newTransforms(Document doc) throws org.apache.xml.security.transforms.TransformationException {
        Transforms t = new Transforms(doc);
        t.addTransform(Transforms.TRANSFORM_C14N_EXCL_OMIT_COMMENTS);
        return t;
    }

    private Element requireElement(Document doc, String ns, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS(ns, localName);
        if (nodes.getLength() == 0) {
            throw new IllegalStateException("Required SOAP element not found: " + localName);
        }
        return (Element) nodes.item(0);
    }
}
