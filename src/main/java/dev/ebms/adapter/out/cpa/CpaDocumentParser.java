package dev.ebms.adapter.out.cpa;

import dev.ebms.domain.Cpa;
import dev.ebms.domain.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Projects a stored document into the {@link Cpa} the message paths use.
 *
 * <p>One projection path, used by every caller: the upload, the startup load and the cache
 * clear. Two formats, and they are not equals -- CPPA 2.0 XML is what cpa-service uploads and
 * what the agreement actually is; YAML is a convenience for the file bootstrap and is never
 * accepted over the API.
 */
@Component
public class CpaDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(CpaDocumentParser.class);

    public static final String XML = "application/xml";
    public static final String YAML = "application/yaml";

    private final YAMLMapper yaml = YAMLMapper.builder().build();
    private final CpaXmlParser xmlParser = new CpaXmlParser();
    private final Path certificateBase;
    private final List<String> ourPartyIds;

    public CpaDocumentParser(
            @Value("${ebms.cpa-directory}") String cpaDirectory,
            @Value("${ebms.host-party-ids:}") List<String> ourPartyIds) {
        this.certificateBase = Path.of(cpaDirectory);
        this.ourPartyIds = ourPartyIds;
    }

    public static String contentTypeOf(Path file) {
        String name = file.toString();
        return name.endsWith(".yml") || name.endsWith(".yaml") ? YAML : XML;
    }

    /**
     * @param source a label for errors and logs -- a filename or the cpaId, never read from disk
     * @throws Exception whatever the underlying parser raises; the caller decides whether one
     *                   bad document fails a request or is skipped with a log
     */
    public Cpa project(String document, String contentType, String source) throws Exception {
        if (YAML.equals(contentType)) {
            return projectYaml(document, source);
        }
        return xmlParser.parse(document.getBytes(StandardCharsets.UTF_8), source, ourPartyIds);
    }

    private Cpa projectYaml(String document, String source) {
        CpaFileConfig config = yaml.readValue(document, CpaFileConfig.class);
        return new Cpa(
                config.cpaId,
                new Party(config.fromParty.partyId, config.fromParty.partyIdType,
                        config.fromParty.role),
                new Party(config.toParty.partyId, config.toParty.partyIdType,
                        config.toParty.role),
                config.transportUrl,
                config.ackRequested,
                config.duplicateElimination,
                config.retries,
                Duration.ofSeconds(config.retryIntervalSeconds),
                loadCertificate(config.recipientCertPath, source));
    }

    /**
     * The YAML format names the partner certificate by path, which cannot survive the move into
     * the database -- so it is resolved against the bootstrap directory, which is still mounted.
     * Uploads are unaffected: CPPA XML embeds the certificate.
     */
    private X509Certificate loadCertificate(String certPath, String source) {
        if (certPath == null || certPath.isBlank()) {
            return null;
        }
        Path resolved = certificateBase.resolve(certPath);
        try {
            byte[] bytes = Files.readAllBytes(resolved);
            String pem = new String(bytes, StandardCharsets.UTF_8)
                    .replaceAll("-----[^-]+-----", "").replaceAll("\\s+", "");
            byte[] der = pem.isEmpty() ? bytes : Base64.getDecoder().decode(pem);
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            log.warn("CPA {}: failed to load recipient cert from {}: {}",
                    source, resolved, e.getMessage());
            return null;
        }
    }
}
