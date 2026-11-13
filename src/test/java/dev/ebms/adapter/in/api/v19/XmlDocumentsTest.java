package dev.ebms.adapter.in.api.v19;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The document decides its own encoding.
 *
 * <p>cpa-service posts a CPA as a {@code text/plain} body without a charset it controls, so
 * decoding by the HTTP header would mean decoding by a default. A CPA carrying a non-ASCII
 * party name would be stored mangled and nothing downstream would notice.
 */
class XmlDocumentsTest {

    private static final String ACCENTED = "Beheerder café";

    @Test
    void prologDeclaringIso8859_1_isDecodedAsIso8859_1() {
        String document = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><cpa>" + ACCENTED + "</cpa>";

        String decoded = XmlDocuments.decode(document.getBytes(StandardCharsets.ISO_8859_1));

        assertThat(decoded).contains(ACCENTED);
    }

    @Test
    void prologDeclaringUtf8_isDecodedAsUtf8() {
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><cpa>" + ACCENTED + "</cpa>";

        String decoded = XmlDocuments.decode(document.getBytes(StandardCharsets.UTF_8));

        assertThat(decoded).contains(ACCENTED);
    }

    /** Real CPAs in this platform ship without a prolog at all. */
    @Test
    void noProlog_fallsBackToUtf8() {
        String decoded = XmlDocuments.decode(("<cpa>" + ACCENTED + "</cpa>")
                .getBytes(StandardCharsets.UTF_8));

        assertThat(decoded).contains(ACCENTED);
    }

    @Test
    void iso8859_1BytesReadAsUtf8_wouldMangle_soThePrologMatters() {
        byte[] latin1 = ("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><cpa>" + ACCENTED + "</cpa>")
                .getBytes(StandardCharsets.ISO_8859_1);

        assertThat(new String(latin1, StandardCharsets.UTF_8))
                .as("what decoding by a default would have produced")
                .doesNotContain(ACCENTED);
        assertThat(XmlDocuments.decode(latin1)).contains(ACCENTED);
    }

    /** A BOM left in front of the prolog breaks every XML parser. */
    @Test
    void utf8Bom_isStripped() {
        byte[] withBom = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                "<cpa/>".getBytes(StandardCharsets.UTF_8));

        assertThat(XmlDocuments.decode(withBom)).startsWith("<cpa");
    }

    @Test
    void utf16Bom_isHonoured() {
        byte[] utf16 = "<cpa>café</cpa>".getBytes(StandardCharsets.UTF_16);

        assertThat(XmlDocuments.decode(utf16)).contains("café");
    }

    @Test
    void unknownEncoding_fallsBackRatherThanThrowing() {
        byte[] document = "<?xml version=\"1.0\" encoding=\"NOT-A-CHARSET\"?><cpa/>"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(XmlDocuments.decode(document)).contains("<cpa/>");
    }

    @Test
    void emptyBody_isEmpty() {
        assertThat(XmlDocuments.decode(new byte[0])).isEmpty();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
