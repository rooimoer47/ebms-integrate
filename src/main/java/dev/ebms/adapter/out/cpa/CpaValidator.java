package dev.ebms.adapter.out.cpa;

import dev.ebms.domain.Cpa;
import dev.ebms.domain.exception.InvalidCpaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * The gate between an upload and our outbound routing.
 *
 * <p>Deliberately the same shape as ebms-core's: XSD-validate, then unmarshal, then a small set
 * of checks. ebms-core's {@code CPAValidator} <em>throws</em> on exactly three conditions -- a
 * {@code Status} of {@code proposed}, a {@code Start} not before {@code End}, and an
 * {@code End} already past -- and logs everything else it notices, including a version other
 * than {@code 2_0b}, multiple channels per action, business signals and multiple endpoints.
 * Being stricter than that is how a cutover rejects CPAs that work today, so we are not.
 *
 * <p>On top of those three we assert the four facts the MSH cannot route without, which are
 * ours rather than ebms-core's: a cpaid, a PartyInfo identifiable as us, a PartyId for both
 * parties, and a transport endpoint for the partner. They are exactly what the projection
 * needs, so projecting the document is the check.
 */
@Component
public class CpaValidator {

    private static final Logger log = LoggerFactory.getLogger(CpaValidator.class);

    /**
     * How much of the validation applies.
     *
     * <p>{@link #FULL} is the network-facing write path, which is where ebms-core applies the
     * XSD. {@link #STRUCTURAL} is the file bootstrap: those documents are operator-supplied on a
     * mounted volume rather than posted by a client, and the YAML form has no XSD at all.
     */
    public enum Strictness { FULL, STRUCTURAL }

    /** What to do about a transport endpoint that is not https. See the constructor. */
    public enum EndpointPolicy { WARN, REJECT }

    private final CpaXsdValidator xsd;
    private final CpaDocumentParser parser;
    private final CpaXmlParser xmlParser = new CpaXmlParser();
    private final EndpointPolicy endpointPolicy;

    /**
     * @param endpointPolicy {@code WARN} matches upstream ebms-core 2.20.x, which only logs a
     *                       non-HTTP transport. {@code REJECT} matches the hardened fork, whose
     *                       {@code cpa.endpoint.validate} refuses cleartext and private-address
     *                       endpoints. Which one is right depends on which image is deployed, so
     *                       it is a property rather than a decision baked in here.
     */
    public CpaValidator(CpaXsdValidator xsd, CpaDocumentParser parser,
                        @Value("${ebms.cpa-endpoint-policy:WARN}") EndpointPolicy endpointPolicy) {
        this.xsd = xsd;
        this.parser = parser;
        this.endpointPolicy = endpointPolicy;
    }

    /** @throws InvalidCpaException with the reason, which becomes the body of the 400 */
    public Cpa validate(String document, String contentType, String source, Strictness strictness) {
        if (strictness == Strictness.FULL && CpaDocumentParser.XML.equals(contentType)) {
            validateAgainstSchema(document, source);
            validateLifecycle(document, source);
        }
        Cpa cpa = project(document, contentType, source);
        validateRouting(cpa, source);
        return cpa;
    }

    private void validateAgainstSchema(String document, String source) {
        try {
            xsd.validate(document);
        } catch (Exception e) {
            throw new InvalidCpaException(
                    "CPA " + source + " is not valid against the CPPA 2.0 schema: "
                            + e.getMessage(), e);
        }
    }

    /** The three conditions ebms-core rejects on, and nothing beyond them. */
    private void validateLifecycle(String document, String source) {
        CpaXmlParser.Lifecycle lifecycle;
        try {
            lifecycle = xmlParser.readLifecycle(document.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new InvalidCpaException(
                    "CPA " + source + " has an unreadable lifecycle: " + e.getMessage(), e);
        }

        if ("proposed".equalsIgnoreCase(lifecycle.status())) {
            throw new InvalidCpaException("CPA " + source + " has status proposed", null);
        }
        if (lifecycle.start() != null && lifecycle.end() != null
                && !lifecycle.start().isBefore(lifecycle.end())) {
            throw new InvalidCpaException(
                    "CPA " + source + " starts at " + lifecycle.start()
                            + ", which is not before its end at " + lifecycle.end(), null);
        }
        if (lifecycle.end() != null && !Instant.now().isBefore(lifecycle.end())) {
            throw new InvalidCpaException(
                    "CPA " + source + " expired on " + lifecycle.end(), null);
        }
    }

    private Cpa project(String document, String contentType, String source) {
        try {
            return parser.project(document, contentType, source);
        } catch (Exception e) {
            throw new InvalidCpaException("CPA " + source + " is invalid: " + e.getMessage(), e);
        }
    }

    private void validateRouting(Cpa cpa, String source) {
        if (cpa.transportUrl() == null || cpa.transportUrl().isBlank()) {
            throw new InvalidCpaException("CPA " + source
                    + " has no Transport/TransportReceiver/Endpoint for the partner, "
                    + "so there is nowhere to send to", null);
        }
        checkEndpointScheme(cpa, source);
    }

    private void checkEndpointScheme(Cpa cpa, String source) {
        String scheme;
        try {
            scheme = URI.create(cpa.transportUrl()).getScheme();
        } catch (IllegalArgumentException e) {
            throw new InvalidCpaException(
                    "CPA " + source + " has an unusable endpoint " + cpa.transportUrl(), e);
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        String message = "CPA " + source + " sends to a cleartext endpoint: " + cpa.transportUrl();
        if (endpointPolicy == EndpointPolicy.REJECT) {
            throw new InvalidCpaException(message, null);
        }
        log.warn("{} (ebms.cpa-endpoint-policy=WARN)", message);
    }
}
