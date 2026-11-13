package dev.ebms.adapter.out.cpa;

import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates a CPA against the vendored CPPA 2.0 schema.
 *
 * <p>Ported from ebms-core's {@code XSDValidator}, which solves the problem this has to solve:
 * the schema imports three siblings by relative {@code schemaLocation}, but external DTD and
 * schema access are switched off -- so an attacker-supplied document cannot pull in a local
 * file or a remote schema -- and with them off the parser cannot resolve those siblings either.
 * Both are pre-loaded from the classpath instead, so nothing is ever fetched.
 *
 * <p>Matching ebms-core here is the point: a CPA it accepts today has to be one we accept at
 * cutover.
 */
@Component
public class CpaXsdValidator {

    private static final String BASE = "/xsd/";
    private static final String CPPA = BASE + "cpp-cpa-2_0.xsd";

    /** The relative imports the bundled schemas use to reference each other. */
    private static final Pattern SCHEMA_LOCATION =
            Pattern.compile("schemaLocation\\s*=\\s*[\"']([^\"']+)[\"']");

    private final Schema schema;

    public CpaXsdValidator() {
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            List<StreamSource> sources = new ArrayList<>(siblingSchemasOf(CPPA));
            sources.add(source(CPPA));
            this.schema = factory.newSchema(sources.toArray(new StreamSource[0]));
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("Cannot compile the CPPA 2.0 schema from " + CPPA, e);
        }
    }

    /**
     * @throws SAXException with the failing element and line, which becomes the body of the 400
     *                      an operator reads in the console
     */
    public void validate(String document) throws SAXException, IOException {
        // Schema is thread-safe; Validator is not, so one per call.
        Validator validator = schema.newValidator();
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try {
            validator.validate(new StreamSource(
                    new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8))));
        } catch (SAXParseException e) {
            throw new SAXException(
                    "line " + e.getLineNumber() + ", column " + e.getColumnNumber() + ": "
                            + e.getMessage(), e);
        }
    }

    private List<StreamSource> siblingSchemasOf(String resource) throws IOException {
        List<StreamSource> sources = new ArrayList<>();
        Matcher matcher = SCHEMA_LOCATION.matcher(read(resource));
        while (matcher.find()) {
            String location = matcher.group(1);
            // Absolute references stay blocked by the accessExternalSchema restriction above.
            if (location.startsWith("http") || location.startsWith("file:")
                    || location.startsWith("jar:")) {
                continue;
            }
            sources.add(source(BASE + location));
        }
        return sources;
    }

    private String read(String resource) throws IOException {
        try (InputStream in = open(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private StreamSource source(String resource) throws IOException {
        URL url = CpaXsdValidator.class.getResource(resource);
        if (url == null) {
            throw new IOException("Missing vendored schema " + resource);
        }
        return new StreamSource(url.openStream(), url.toString());
    }

    private InputStream open(String resource) throws IOException {
        InputStream in = CpaXsdValidator.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("Missing vendored schema " + resource);
        }
        return in;
    }
}
