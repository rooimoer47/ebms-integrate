package dev.ebms.adapter.out.cpa;

import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.Party;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Repository
public class YamlCpaRepository implements CpaRepository {

    private static final Logger log = LoggerFactory.getLogger(YamlCpaRepository.class);

    private final Map<String, Cpa> cpas = new ConcurrentHashMap<>();
    private final Path cpaDirectory;
    private final String ourPartyId;
    private final YAMLMapper yaml = YAMLMapper.builder().build();
    private final CpaXmlParser xmlParser = new CpaXmlParser();

    public YamlCpaRepository(
            @Value("${ebms.cpa-directory}") String cpaDirectory,
            @Value("${ebms.host-party-id:}") String ourPartyId) {
        this.cpaDirectory = Path.of(cpaDirectory);
        this.ourPartyId = ourPartyId;
    }

    @PostConstruct
    public void reload() {
        cpas.clear();
        if (!Files.exists(cpaDirectory)) {
            log.warn("CPA directory does not exist: {}", cpaDirectory);
            return;
        }
        try (Stream<Path> files = Files.list(cpaDirectory)) {
            files.filter(p -> {
                        String name = p.toString();
                        return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".xml");
                    })
                    .forEach(this::loadFile);
        } catch (IOException e) {
            log.error("Failed to read CPA directory: {}", cpaDirectory, e);
        }
        log.info("Loaded {} CPA(s) from {}", cpas.size(), cpaDirectory);
    }

    private X509Certificate loadCertFromPath(String certPath, Path cpaFile) {
        if (certPath == null || certPath.isBlank()) return null;
        Path resolved = cpaFile.getParent() != null
                ? cpaFile.getParent().resolve(certPath)
                : Path.of(certPath);
        try {
            byte[] bytes = Files.readAllBytes(resolved);
            // Strip PEM headers if present
            String pem = new String(bytes).replaceAll("-----[^-]+-----", "").replaceAll("\\s+", "");
            byte[] der = pem.isEmpty() ? bytes : java.util.Base64.getDecoder().decode(pem);
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            log.warn("Failed to load recipient cert from {}: {}", resolved, e.getMessage());
            return null;
        }
    }

    @Override
    public Optional<Cpa> findByCpaId(String cpaId) {
        return Optional.ofNullable(cpas.get(cpaId));
    }

    @Override
    public List<Cpa> findAll() {
        return List.copyOf(cpas.values());
    }

    private void loadFile(Path file) {
        try {
            Cpa cpa;
            if (file.toString().endsWith(".xml")) {
                cpa = xmlParser.parse(file, ourPartyId);
            } else {
                CpaFileConfig config = yaml.readValue(file.toFile(), CpaFileConfig.class);
                X509Certificate cert = loadCertFromPath(config.recipientCertPath, file);
                cpa = new Cpa(
                        config.cpaId,
                        new Party(config.fromParty.partyId, config.fromParty.partyIdType),
                        new Party(config.toParty.partyId, config.toParty.partyIdType),
                        config.transportUrl,
                        config.ackRequested,
                        config.duplicateElimination,
                        config.retries,
                        Duration.ofSeconds(config.retryIntervalSeconds),
                        cert
                );
            }
            cpas.put(cpa.cpaId(), cpa);
            log.debug("Loaded CPA {} from {}", cpa.cpaId(), file.getFileName());
        } catch (Exception e) {
            log.error("Failed to parse CPA file: {}", file, e);
        }
    }
}
