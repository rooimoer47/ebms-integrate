package dev.ebms.adapter.in.api.v19;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an uploaded XML body into text using the encoding the document declares.
 *
 * <p>The HTTP charset is deliberately not consulted. cpa-service posts a CPA as a
 * {@code text/plain} body without setting a charset it controls, so Spring's
 * {@code StringHttpMessageConverter} would decode with a default -- and a CPA carrying a
 * non-ASCII party name would be stored mangled, with nothing downstream to notice. The prolog
 * is the only thing that actually knows.
 */
final class XmlDocuments {

    /** Only the prolog is scanned, which is ASCII-compatible in every encoding we can meet. */
    private static final int PROLOG_BYTES = 200;

    private static final Pattern ENCODING =
            Pattern.compile("<\\?xml[^>]*encoding\\s*=\\s*[\"']([^\"']+)[\"']");

    private XmlDocuments() {
    }

    static String decode(byte[] body) {
        if (body.length == 0) {
            return "";
        }
        String text = new String(body, charsetOf(body));
        // A BOM left in the string would sit before the prolog and break every XML parser.
        return text.isEmpty() || text.charAt(0) != '\uFEFF' ? text : text.substring(1);
    }

    private static Charset charsetOf(byte[] body) {
        Charset fromBom = bomCharset(body);
        if (fromBom != null) {
            return fromBom;
        }
        String prolog = new String(body, 0, Math.min(body.length, PROLOG_BYTES),
                StandardCharsets.ISO_8859_1);
        Matcher matcher = ENCODING.matcher(prolog);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                // An encoding the JVM does not know is the document's problem, not ours to
                // guess at: fall through to UTF-8 and let the XML parser report it.
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset bomCharset(byte[] body) {
        if (body.length >= 2 && (body[0] & 0xFF) == 0xFE && (body[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        if (body.length >= 2 && (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (body.length >= 3 && (body[0] & 0xFF) == 0xEF && (body[1] & 0xFF) == 0xBB
                && (body[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        return null;
    }
}
